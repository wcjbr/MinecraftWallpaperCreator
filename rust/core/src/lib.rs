use std::collections::HashMap;
use std::collections::VecDeque;
use std::fmt::Write as _;
use std::fs;
use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::str::FromStr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex, Once};
use std::thread;
use image::{DynamicImage, ImageBuffer, Rgb, RgbImage};
use ndarray::Array4;
use ort::session::{builder::GraphOptimizationLevel, Session};
use ort::value::{TensorElementType, ValueType};
use thiserror::Error;

#[derive(Debug, Clone)]
pub struct CoreConfig {
    pub target_fps: u32,
    pub playback_fps: u32,
    pub export_format: ExportFormat,
    pub rife_threads: usize,
    pub max_memory_mb: usize,
    pub auto_blend_loop: bool,
    pub blend_frame_count: usize,
}

#[derive(Debug, Clone)]
pub struct CoreResult {
    pub playlist: PathBuf,
    pub loop_info: PathBuf,
    pub script: PathBuf,
    pub script_bat: PathBuf,
    pub mkv: Option<PathBuf>,
    pub mkv_script: PathBuf,
    pub mkv_script_bat: PathBuf,
    pub transition_frames: usize,
    pub base_frames: usize,
    pub loop_start_frame: usize,
    pub selection_score: f64,
}

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("image error: {0}")]
    Image(#[from] image::ImageError),
    #[error("onnx runtime error: {0}")]
    Ort(#[from] ort::Error),
    #[error("no PNG frames found in {0}")]
    NoFrames(PathBuf),
    #[error("need at least 2 frames, found {0}")]
    NotEnoughFrames(usize),
    #[error("RIFE model not found; checked: {0}")]
    ModelNotFound(String),
    #[error("unsupported RIFE model signature: {0}")]
    UnsupportedModel(String),
    #[error("invalid model output: {0}")]
    InvalidOutput(String),
    #[error("invalid export format: {0}")]
    InvalidExportFormat(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportFormat {
    PngSequence,
    MkvH264,
    MkvH265,
    Mp4H264,
    WebmVp9,
}

impl ExportFormat {
    fn extension(self) -> Option<&'static str> {
        match self {
            Self::PngSequence => None,
            Self::MkvH264 | Self::MkvH265 => Some("mkv"),
            Self::Mp4H264 => Some("mp4"),
            Self::WebmVp9 => Some("webm"),
        }
    }

    fn ffmpeg_args(self) -> &'static [&'static str] {
        match self {
            Self::PngSequence => &[],
            Self::MkvH264 => &["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p"],
            Self::MkvH265 => &["-c:v", "libx265", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p"],
            Self::Mp4H264 => &["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart"],
            Self::WebmVp9 => &["-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "30", "-pix_fmt", "yuv420p"],
        }
    }
}

impl FromStr for ExportFormat {
    type Err = CoreError;

    fn from_str(value: &str) -> std::result::Result<Self, Self::Err> {
        match value {
            "PNG_SEQUENCE" => Ok(Self::PngSequence),
            "MKV_H264" => Ok(Self::MkvH264),
            "MKV_H265" => Ok(Self::MkvH265),
            "MP4_H264" => Ok(Self::Mp4H264),
            "WEBM_VP9" => Ok(Self::WebmVp9),
            other => Err(CoreError::InvalidExportFormat(other.to_string())),
        }
    }
}

type Result<T> = std::result::Result<T, CoreError>;
static MODEL_SIGNATURE_LOG_ONCE: Once = Once::new();
const LOOP_SIGNATURE_WIDTH: u32 = 32;
const LOOP_SIGNATURE_HEIGHT: u32 = 32;
const LOOP_LENGTH_BONUS: f64 = 0.10;
const MEMORY_RESERVE_KIB: usize = 2 * 1024 * 1024;
const ESTIMATED_WORKER_MEMORY_KIB: usize = 1024 * 1024;

pub fn process_session(session_dir: &Path, config: &CoreConfig) -> Result<CoreResult> {
    let frames_dir = session_dir.join("frames");
    let transition_dir = session_dir.join("loop-transitions");
    fs::create_dir_all(&transition_dir)?;

    let frame_paths = collect_frame_paths(&frames_dir)?;
    if frame_paths.len() < 2 {
        return Err(CoreError::NotEnoughFrames(frame_paths.len()));
    }

    let model_path = discover_model_path(session_dir)?;
    println!("progress init-runtime");
    let _ = ort::init().commit();

    println!("progress rife-model={}", model_path.display());
    println!("progress onnxruntime=bundled");

    let frame_signatures = build_frame_signatures(&frame_paths)?;
    let loop_selection = if config.auto_blend_loop {
        find_best_loop_selection(&frame_signatures)
    } else {
        default_loop_selection(&frame_signatures)
    };
    println!(
        "progress loop-selection start={} end={} frames={} score={:.6}",
        loop_selection.start_index,
        loop_selection.end_index_inclusive,
        loop_selection.frame_count(),
        loop_selection.selection_score
    );

    let selected_frame_paths =
        frame_paths[loop_selection.start_index..=loop_selection.end_index_inclusive].to_vec();

    let mut interpolator = if config.playback_fps > config.target_fps {
        println!("progress load-rife-session");
        Some(RifeInterpolator::load(&model_path)?)
    } else {
        None
    };

    let loop_start_frame = loop_selection.start_index;
    let base_frames = selected_frame_paths.len();
    let output_frames = build_output_plan(base_frames, config.target_fps, config.playback_fps);
    let exact_frame_refs: Vec<PathBuf> = selected_frame_paths
        .iter()
        .map(|p| relative_to(session_dir, p))
        .collect();

    let mut transition_frames = 0usize;
    let mut playlist_entries = vec![PathBuf::new(); output_frames.len()];
    let mut interpolation_tasks = Vec::<InterpolationTask>::new();

    for (index, sample) in output_frames.iter().enumerate() {
        if let Some(source_index) = sample.exact_frame_index {
            playlist_entries[index] = exact_frame_refs[source_index].clone();
            continue;
        }

        let Some(_) = interpolator.as_mut() else {
            playlist_entries[index] = exact_frame_refs[sample.left_index].clone();
            continue;
        };

        let rel_path = PathBuf::from("loop-transitions").join(format!("interp-{index:05}.png"));
        playlist_entries[index] = rel_path.clone();
        interpolation_tasks.push(InterpolationTask {
            output_index: index,
            left_frame_path: selected_frame_paths[sample.left_index].clone(),
            right_frame_path: selected_frame_paths[sample.right_index].clone(),
            rel_output_path: rel_path,
            abs_output_path: session_dir.join(format!("loop-transitions/interp-{index:05}.png")),
            left_index: sample.left_index,
            right_index: sample.right_index,
            t: sample.t_numer as f32 / sample.t_denom as f32,
        });
    }

    if !interpolation_tasks.is_empty() {
        drop(interpolator.take());
        transition_frames = run_parallel_interpolation_tasks(
            &model_path,
            interpolation_tasks,
            output_frames.len(),
            config.rife_threads,
            config.max_memory_mb,
        )?;
    }

    let playlist = session_dir.join("playlist.m3u");
    let loop_info = session_dir.join("loop-info.properties");
    let script = session_dir.join("wallpaper-loop.sh");
    let script_bat = session_dir.join("wallpaper-loop.bat");
    let ffmpeg_concat = session_dir.join("ffmpeg-concat.txt");
    let mkv_script = session_dir.join("export-wallpaper-video.sh");
    let mkv_script_bat = session_dir.join("export-wallpaper-video.bat");
    let mkv_path = config
        .export_format
        .extension()
        .map(|ext| session_dir.join(format!("wallpaper-loop.{ext}")));

    write_playlist(&playlist, &playlist_entries)?;
    write_ffmpeg_concat(&ffmpeg_concat, &playlist_entries, config.playback_fps)?;
    write_mkv_script(&mkv_script, &ffmpeg_concat, config.export_format, mkv_path.as_deref())?;
    write_mkv_script_bat(&mkv_script_bat, &ffmpeg_concat, config.export_format, mkv_path.as_deref())?;
    let mkv_export =
        export_mkv_if_available(session_dir, &ffmpeg_concat, config.export_format, mkv_path.as_deref())?;
    write_loop_info(
        &loop_info,
        &model_path,
        frame_paths.len(),
        base_frames,
        output_frames.len(),
        transition_frames,
        config,
        loop_start_frame,
        loop_selection.selection_score,
        &mkv_export,
        mkv_path.as_deref(),
        &mkv_script,
        &mkv_script_bat,
    )?;
    write_script(&script, config.playback_fps)?;
    write_script_bat(&script_bat, config.playback_fps)?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for path in [&script, &mkv_script] {
            let mut perms = fs::metadata(path)?.permissions();
            perms.set_mode(0o755);
            fs::set_permissions(path, perms)?;
        }
    }

    Ok(CoreResult {
        playlist,
        loop_info,
        script,
        script_bat,
        mkv: mkv_export.output_path.clone(),
        mkv_script,
        mkv_script_bat,
        transition_frames,
        base_frames,
        loop_start_frame,
        selection_score: loop_selection.selection_score,
    })
}

fn collect_frame_paths(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut frames = Vec::new();
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let is_png = path
            .extension()
            .and_then(|s| s.to_str())
            .map(|s| s.eq_ignore_ascii_case("png"))
            .unwrap_or(false);
        if is_png {
            frames.push(path);
        }
    }
    frames.sort();
    if frames.is_empty() {
        return Err(CoreError::NoFrames(dir.to_path_buf()));
    }
    Ok(frames)
}

fn discover_model_path(session_dir: &Path) -> Result<PathBuf> {
    let candidates = [
        std::env::var_os("MWC_RIFE_MODEL").map(PathBuf::from),
        Some(session_dir.join("RIFE_HDv3.onnx")),
        Some(PathBuf::from("/home/archzero/下载/RIFE_HDv3.onnx")),
        Some(PathBuf::from("/tmp/RIFE_HDv3.onnx")),
    ];
    discover_existing_path(&candidates).ok_or_else(|| {
        let checked = flatten_candidates(&candidates);
        CoreError::ModelNotFound(checked)
    })
}

fn discover_existing_path(candidates: &[Option<PathBuf>]) -> Option<PathBuf> {
    candidates
        .iter()
        .flatten()
        .find(|path| path.exists())
        .cloned()
}

fn flatten_candidates(candidates: &[Option<PathBuf>]) -> String {
    let mut joined = String::new();
    for (index, item) in candidates.iter().flatten().enumerate() {
        if index > 0 {
            joined.push_str(", ");
        }
        let _ = write!(&mut joined, "{}", item.display());
    }
    joined
}

fn relative_to(base: &Path, path: &Path) -> PathBuf {
    path.strip_prefix(base).unwrap_or(path).to_path_buf()
}

fn load_rgb_image(path: &Path) -> Result<RgbImage> {
    Ok(image::open(path)?.to_rgb8())
}

fn build_frame_signatures(frame_paths: &[PathBuf]) -> Result<Vec<Vec<u8>>> {
    frame_paths
        .iter()
        .map(|path| build_frame_signature(path))
        .collect()
}

fn build_frame_signature(path: &Path) -> Result<Vec<u8>> {
    Ok(DynamicImage::ImageRgb8(load_rgb_image(path)?)
        .resize_exact(
            LOOP_SIGNATURE_WIDTH,
            LOOP_SIGNATURE_HEIGHT,
            image::imageops::FilterType::Triangle,
        )
        .to_rgb8()
        .into_raw())
}

fn signature_similarity(a: &[u8], b: &[u8]) -> f64 {
    let mut error = 0f64;
    for (&left, &right) in a.iter().zip(b.iter()) {
        let diff = f64::from(left) - f64::from(right);
        error += diff * diff;
    }
    let rmse = (error / a.len().max(1) as f64).sqrt() / 255.0;
    (1.0 - rmse).clamp(0.0, 1.0)
}

fn default_loop_selection(signatures: &[Vec<u8>]) -> LoopSelection {
    let end_index_inclusive = signatures.len().saturating_sub(1);
    let selection_score = if signatures.len() >= 2 {
        signature_similarity(&signatures[0], &signatures[end_index_inclusive])
    } else {
        1.0
    };
    LoopSelection {
        start_index: 0,
        end_index_inclusive,
        selection_score,
    }
}

fn find_best_loop_selection(signatures: &[Vec<u8>]) -> LoopSelection {
    let frame_count = signatures.len();
    if frame_count < 2 {
        return default_loop_selection(signatures);
    }

    let max_span = frame_count - 1;
    let min_span = choose_loop_min_span(frame_count);
    let mut best = default_loop_selection(signatures);
    let mut best_objective = loop_selection_objective(
        best.selection_score,
        best.end_index_inclusive - best.start_index,
        max_span,
    );

    for start_index in 0..frame_count - 1 {
        let min_end = start_index + min_span;
        if min_end >= frame_count {
            break;
        }

        for end_index_inclusive in min_end..frame_count {
            let similarity =
                signature_similarity(&signatures[start_index], &signatures[end_index_inclusive]);
            let span = end_index_inclusive - start_index;
            let objective = loop_selection_objective(similarity, span, max_span);
            let better = objective > best_objective + f64::EPSILON
                || ((objective - best_objective).abs() <= f64::EPSILON
                    && (span > (best.end_index_inclusive - best.start_index)
                        || (span == (best.end_index_inclusive - best.start_index)
                            && similarity > best.selection_score)));
            if better {
                best = LoopSelection {
                    start_index,
                    end_index_inclusive,
                    selection_score: similarity,
                };
                best_objective = objective;
            }
        }
    }

    best
}

fn choose_loop_min_span(frame_count: usize) -> usize {
    let max_span = frame_count.saturating_sub(1);
    if max_span <= 1 {
        return max_span.max(1);
    }
    ((max_span + 2) / 3).clamp(2, max_span)
}

fn loop_selection_objective(similarity: f64, span: usize, max_span: usize) -> f64 {
    let span_ratio = if max_span == 0 {
        1.0
    } else {
        span as f64 / max_span as f64
    };
    similarity + span_ratio * LOOP_LENGTH_BONUS
}

fn write_playlist(path: &Path, entries: &[PathBuf]) -> Result<()> {
    let mut text = String::new();
    text.push_str("#EXTM3U\n");
    for entry in entries {
        let _ = writeln!(&mut text, "{}", entry.display());
    }
    fs::write(path, text)?;
    Ok(())
}

fn write_ffmpeg_concat(path: &Path, entries: &[PathBuf], playback_fps: u32) -> Result<()> {
    let mut text = String::new();
    let duration = 1.0f64 / f64::from(playback_fps.max(1));
    for entry in entries {
        let escaped = ffmpeg_quote_path(entry);
        let _ = writeln!(&mut text, "file '{}'", escaped);
        let _ = writeln!(&mut text, "duration {duration:.9}");
    }
    if let Some(last) = entries.last() {
        let escaped = ffmpeg_quote_path(last);
        let _ = writeln!(&mut text, "file '{}'", escaped);
    }
    fs::write(path, text)?;
    Ok(())
}

fn write_mkv_script(
    path: &Path,
    concat_path: &Path,
    export_format: ExportFormat,
    mkv_path: Option<&Path>,
) -> Result<()> {
    let concat_name = concat_path
        .file_name()
        .and_then(|it| it.to_str())
        .unwrap_or("ffmpeg-concat.txt");
    let script = if let Some(mkv_path) = mkv_path {
        let mkv_name = mkv_path
            .file_name()
            .and_then(|it| it.to_str())
            .unwrap_or("wallpaper-loop.mkv");
        let ffmpeg_args = ffmpeg_args_string(export_format.ffmpeg_args());
        format!(
            "#!/usr/bin/env bash\nset -euo pipefail\nSCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\ncd \"$SCRIPT_DIR\"\nffmpeg -y -f concat -safe 0 -i {concat_name:?} -fps_mode vfr -vf \"pad=ceil(iw/2)*2:ceil(ih/2)*2\" {ffmpeg_args} {mkv_name:?}\n"
        )
    } else {
        "#!/usr/bin/env bash\nset -euo pipefail\necho \"PNG sequence export selected; no video container will be generated.\"\n".to_string()
    };
    fs::write(path, script)?;
    Ok(())
}

fn write_mkv_script_bat(
    path: &Path,
    concat_path: &Path,
    export_format: ExportFormat,
    mkv_path: Option<&Path>,
) -> Result<()> {
    let concat_name = concat_path
        .file_name()
        .and_then(|it| it.to_str())
        .unwrap_or("ffmpeg-concat.txt");
    let script = if let Some(mkv_path) = mkv_path {
        let mkv_name = mkv_path
            .file_name()
            .and_then(|it| it.to_str())
            .unwrap_or("wallpaper-loop.mkv");
        let ffmpeg_args = ffmpeg_args_string(export_format.ffmpeg_args());
        format!(
            "@echo off\r\nsetlocal\r\ncd /d \"%~dp0\"\r\nffmpeg -y -f concat -safe 0 -i \"{concat_name}\" -fps_mode vfr -vf \"pad=ceil(iw/2)*2:ceil(ih/2)*2\" {ffmpeg_args} \"{mkv_name}\"\r\n"
        )
    } else {
        "@echo off\r\necho PNG sequence export selected; no video container will be generated.\r\n".to_string()
    };
    fs::write(path, script)?;
    Ok(())
}

fn export_mkv_if_available(
    session_dir: &Path,
    concat_path: &Path,
    export_format: ExportFormat,
    mkv_path: Option<&Path>,
) -> Result<MkvExportResult> {
    let Some(mkv_path) = mkv_path else {
        println!("progress video-export=skipped reason=png-sequence");
        return Ok(MkvExportResult {
            status: MkvExportStatus::Skipped("png-sequence".to_string()),
            output_path: None,
        });
    };
    let concat_name = concat_path
        .file_name()
        .and_then(|it| it.to_str())
        .unwrap_or("ffmpeg-concat.txt");
    let mkv_name = mkv_path
        .file_name()
        .and_then(|it| it.to_str())
        .unwrap_or("wallpaper-loop.mkv");

    let mut command = Command::new("ffmpeg");
    command
        .arg("-y")
        .arg("-f")
        .arg("concat")
        .arg("-safe")
        .arg("0")
        .arg("-i")
        .arg(concat_name)
        .arg("-fps_mode")
        .arg("vfr")
        .arg("-vf")
        .arg("pad=ceil(iw/2)*2:ceil(ih/2)*2");
    for arg in export_format.ffmpeg_args() {
        command.arg(arg);
    }
    let output = command.arg(mkv_name).current_dir(session_dir).output();

    match output {
        Ok(output) if output.status.success() => {
            println!("progress video-export=ready path={}", mkv_path.display());
            Ok(MkvExportResult {
                status: MkvExportStatus::Ready,
                output_path: Some(mkv_path.to_path_buf()),
            })
        }
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let summary = stderr.lines().last().unwrap_or("ffmpeg failed");
            println!("progress video-export=failed reason={summary}");
            Ok(MkvExportResult {
                status: MkvExportStatus::Failed(summary.to_string()),
                output_path: None,
            })
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
            println!("progress video-export=skipped reason=ffmpeg-not-found");
            Ok(MkvExportResult {
                status: MkvExportStatus::Skipped("ffmpeg-not-found".to_string()),
                output_path: None,
            })
        }
        Err(err) => Err(CoreError::Io(err)),
    }
}

fn ffmpeg_quote_path(path: &Path) -> String {
    path.to_string_lossy().replace('\'', "'\\''")
}

fn ffmpeg_args_string(args: &[&str]) -> String {
    args.iter()
        .map(|arg| {
            if arg.contains(' ') || arg.contains('"') {
                format!("{arg:?}")
            } else {
                (*arg).to_string()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn write_loop_info(
    path: &Path,
    model_path: &Path,
    source_frames: usize,
    base_frames: usize,
    output_frames: usize,
    transition_frames: usize,
    config: &CoreConfig,
    loop_start_frame: usize,
    selection_score: f64,
    mkv_export: &MkvExportResult,
    mkv_path: Option<&Path>,
    mkv_script: &Path,
    mkv_script_bat: &Path,
) -> Result<()> {
    let mut text = String::new();
    let _ = writeln!(&mut text, "rife_model={}", model_path.display());
    let _ = writeln!(&mut text, "onnxruntime=bundled");
    let _ = writeln!(&mut text, "source_frames={source_frames}");
    let _ = writeln!(&mut text, "base_frames={base_frames}");
    let _ = writeln!(&mut text, "output_frames={output_frames}");
    let _ = writeln!(&mut text, "transition_frames={transition_frames}");
    let _ = writeln!(&mut text, "capture_fps={}", config.target_fps);
    let _ = writeln!(&mut text, "playback_fps={}", config.playback_fps);
    let _ = writeln!(&mut text, "export_format={:?}", config.export_format);
    let _ = writeln!(&mut text, "rife_threads={}", config.rife_threads);
    let _ = writeln!(&mut text, "max_memory_mb={}", config.max_memory_mb);
    let _ = writeln!(&mut text, "auto_blend_loop={}", config.auto_blend_loop);
    let _ = writeln!(&mut text, "blend_frame_count={}", config.blend_frame_count);
    let _ = writeln!(&mut text, "loop_start_frame={loop_start_frame}");
    let _ = writeln!(
        &mut text,
        "loop_end_frame_exclusive={}",
        loop_start_frame + base_frames
    );
    let _ = writeln!(&mut text, "selection_score={selection_score:.6}");
    let _ = writeln!(
        &mut text,
        "mkv_path={}",
        mkv_path.map(|it| it.display().to_string()).unwrap_or_default()
    );
    let _ = writeln!(&mut text, "mkv_script={}", mkv_script.display());
    let _ = writeln!(&mut text, "mkv_script_bat={}", mkv_script_bat.display());
    let _ = writeln!(&mut text, "mkv_status={}", mkv_export.status.as_str());
    if let Some(reason) = mkv_export.status.reason() {
        let _ = writeln!(&mut text, "mkv_reason={reason}");
    }
    fs::write(path, text)?;
    Ok(())
}

fn write_script(path: &Path, playback_fps: u32) -> Result<()> {
    let duration = 1.0f64 / f64::from(playback_fps.max(1));
    let script = format!(
        "#!/usr/bin/env bash\nset -euo pipefail\nSCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\ncd \"$SCRIPT_DIR\"\nmpv --loop-playlist=inf --image-display-duration={duration:.9} playlist.m3u\n"
    );
    fs::write(path, script)?;
    Ok(())
}

fn write_script_bat(path: &Path, playback_fps: u32) -> Result<()> {
    let duration = 1.0f64 / f64::from(playback_fps.max(1));
    let script = format!(
        "@echo off\r\nsetlocal\r\ncd /d \"%~dp0\"\r\nmpv --loop-playlist=inf --image-display-duration={duration:.9} playlist.m3u\r\n"
    );
    fs::write(path, script)?;
    Ok(())
}

fn build_output_plan(base_frames: usize, target_fps: u32, playback_fps: u32) -> Vec<OutputSample> {
    let target = target_fps.max(1) as usize;
    let playback = playback_fps.max(target_fps.max(1)) as usize;
    let gcd_tp = gcd(target, playback);
    let p = playback / gcd_tp;
    let q = target / gcd_tp;
    let sample_count = base_frames * p / gcd(q, base_frames);

    let mut plan = Vec::with_capacity(sample_count);
    for j in 0..sample_count {
        let numer = j * q;
        let left_index = (numer / p) % base_frames;
        let t_numer = numer % p;
        if t_numer == 0 {
            plan.push(OutputSample {
                left_index,
                right_index: left_index,
                t_numer: 0,
                t_denom: 1,
                exact_frame_index: Some(left_index),
            });
        } else {
            plan.push(OutputSample {
                left_index,
                right_index: (left_index + 1) % base_frames,
                t_numer,
                t_denom: p,
                exact_frame_index: None,
            });
        }
    }
    plan
}

fn gcd(mut a: usize, mut b: usize) -> usize {
    while b != 0 {
        let next = a % b;
        a = b;
        b = next;
    }
    a.max(1)
}

#[derive(Debug, Clone)]
struct OutputSample {
    left_index: usize,
    right_index: usize,
    t_numer: usize,
    t_denom: usize,
    exact_frame_index: Option<usize>,
}

#[derive(Debug, Clone)]
struct InterpolationTask {
    output_index: usize,
    left_frame_path: PathBuf,
    right_frame_path: PathBuf,
    rel_output_path: PathBuf,
    abs_output_path: PathBuf,
    left_index: usize,
    right_index: usize,
    t: f32,
}

#[derive(Debug)]
struct InterpolationResult {
    output_index: usize,
    rel_output_path: PathBuf,
}

#[derive(Debug, Clone, Copy)]
struct LoopSelection {
    start_index: usize,
    end_index_inclusive: usize,
    selection_score: f64,
}

impl LoopSelection {
    fn frame_count(self) -> usize {
        self.end_index_inclusive
            .saturating_sub(self.start_index)
            .saturating_add(1)
    }
}

#[derive(Debug, Clone)]
struct MkvExportResult {
    status: MkvExportStatus,
    output_path: Option<PathBuf>,
}

#[derive(Debug, Clone)]
enum MkvExportStatus {
    Ready,
    Skipped(String),
    Failed(String),
}

impl MkvExportStatus {
    fn as_str(&self) -> &'static str {
        match self {
            Self::Ready => "ready",
            Self::Skipped(_) => "skipped",
            Self::Failed(_) => "failed",
        }
    }

    fn reason(&self) -> Option<&str> {
        match self {
            Self::Ready => None,
            Self::Skipped(reason) | Self::Failed(reason) => Some(reason.as_str()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TensorLayout {
    Nchw,
    Nhwc,
}

#[derive(Debug, Clone)]
struct ImageTensorSpec {
    name: String,
    layout: TensorLayout,
    channels: usize,
}

#[derive(Debug, Clone)]
struct ScalarTensorSpec {
    name: String,
    dims: Vec<usize>,
}

#[derive(Debug, Clone)]
struct ModelSignature {
    image_inputs: Vec<ImageTensorSpec>,
    timestep_input: Option<ScalarTensorSpec>,
    output_name: String,
    output_layout: TensorLayout,
    output_channels: Option<usize>,
}

struct RifeInterpolator {
    session: Session,
    signature: ModelSignature,
}

impl RifeInterpolator {
    fn load(model_path: &Path) -> Result<Self> {
        println!("progress session-builder");
        let builder = Session::builder()?;
        let builder = builder
            .with_optimization_level(GraphOptimizationLevel::Level3)
            .map_err(ort::Error::from)?;
        let builder = builder.with_intra_threads(1).map_err(ort::Error::from)?;
        println!("progress session-commit");
        let session = builder
            .with_inter_threads(1)
            .map_err(ort::Error::from)?
            .commit_from_file(model_path)?;

        let signature = detect_model_signature(&session)?;
        println!("progress model-signature-ready");
        MODEL_SIGNATURE_LOG_ONCE.call_once(|| {
            log_model_signature(&session, &signature);
        });

        Ok(Self { session, signature })
    }

    fn interpolate(&mut self, left: &RgbImage, right: &RgbImage, t: f32) -> Result<RgbImage> {
        let (width, height) = left.dimensions();
        if right.dimensions() != (width, height) {
            return Err(CoreError::InvalidOutput(format!(
                "frame size mismatch: left={}x{}, right={}x{}",
                width,
                height,
                right.width(),
                right.height()
            )));
        }

        let padded_width = align_dimension(width as usize, 32);
        let padded_height = align_dimension(height as usize, 32);
        let left_padded = pad_image(left, padded_width as u32, padded_height as u32);
        let right_padded = pad_image(right, padded_width as u32, padded_height as u32);

        let outputs = match self.signature.image_inputs.as_slice() {
            [first, second] if first.channels == 3 && second.channels == 3 => {
                let left_tensor = image_to_tensor(&left_padded, first.layout);
                let right_tensor = image_to_tensor(&right_padded, second.layout);
                if let Some(timestep) = &self.signature.timestep_input {
                    let t_tensor = scalar_tensor(&timestep.dims, t);
                    self.session.run(ort::inputs![
                        first.name.as_str() => ort::value::Tensor::from_array(left_tensor)?,
                        second.name.as_str() => ort::value::Tensor::from_array(right_tensor)?,
                        timestep.name.as_str() => ort::value::Tensor::from_array(t_tensor)?,
                    ])?
                } else {
                    self.session.run(ort::inputs![
                        first.name.as_str() => ort::value::Tensor::from_array(left_tensor)?,
                        second.name.as_str() => ort::value::Tensor::from_array(right_tensor)?,
                    ])?
                }
            }
            [combined] if combined.channels == 6 => {
                let combined_tensor = concat_frames(&left_padded, &right_padded, combined.layout);
                if let Some(timestep) = &self.signature.timestep_input {
                    let t_tensor = scalar_tensor(&timestep.dims, t);
                    self.session.run(ort::inputs![
                        combined.name.as_str() => ort::value::Tensor::from_array(combined_tensor)?,
                        timestep.name.as_str() => ort::value::Tensor::from_array(t_tensor)?,
                    ])?
                } else {
                    self.session.run(ort::inputs![
                        combined.name.as_str() => ort::value::Tensor::from_array(combined_tensor)?,
                    ])?
                }
            }
            _ => {
                return Err(CoreError::UnsupportedModel(
                    "expected either 2x RGB image inputs or 1x 6-channel image input".to_string(),
                ));
            }
        };

        let output = outputs
            .get(self.signature.output_name.as_str())
            .ok_or_else(|| CoreError::InvalidOutput(format!("missing output {}", self.signature.output_name)))?;
        let array = output.try_extract_array::<f32>()?;
        tensor_to_image(
            array,
            self.signature.output_layout,
            self.signature.output_channels,
            width,
            height,
        )
    }
}

fn detect_model_signature(session: &Session) -> Result<ModelSignature> {
    let mut image_inputs = Vec::new();
    let mut timestep_input = None;

    MODEL_SIGNATURE_LOG_ONCE.call_once(|| {
        for (index, outlet) in session.inputs().iter().enumerate() {
            println!(
                "progress inspect-input[{index}] name={} dtype={:?}",
                outlet.name(),
                outlet.dtype()
            );
        }
        for (index, outlet) in session.outputs().iter().enumerate() {
            println!(
                "progress inspect-output[{index}] name={} dtype={:?}",
                outlet.name(),
                outlet.dtype()
            );
        }
    });

    for outlet in session.inputs() {
        let ValueType::Tensor { ty, shape, .. } = outlet.dtype() else {
            continue;
        };
        if *ty != TensorElementType::Float32 {
            continue;
        }

        let dims = concrete_dims(shape.iter().copied());
        if dims.len() == 4 {
            if dims[1] == 3 || dims[1] == 6 {
                image_inputs.push(ImageTensorSpec {
                    name: outlet.name().to_string(),
                    layout: TensorLayout::Nchw,
                    channels: dims[1],
                });
                continue;
            }
            if dims[3] == 3 || dims[3] == 6 {
                image_inputs.push(ImageTensorSpec {
                    name: outlet.name().to_string(),
                    layout: TensorLayout::Nhwc,
                    channels: dims[3],
                });
                continue;
            }
        }

        if timestep_input.is_none() {
            timestep_input = Some(ScalarTensorSpec {
                name: outlet.name().to_string(),
                dims: if dims.is_empty() { vec![] } else { dims },
            });
        }
    }

    if image_inputs.is_empty() {
        return Err(CoreError::UnsupportedModel(
            "no float image input tensor detected".to_string(),
        ));
    }

    let mut output_name = None;
    let mut output_layout = None;
    let mut output_channels = None;
    for outlet in session.outputs() {
        let ValueType::Tensor {
            ty,
            shape,
            dimension_symbols,
        } = outlet.dtype()
        else {
            continue;
        };
        if *ty != TensorElementType::Float32 {
            continue;
        }

        let dims = concrete_dims(shape.iter().copied());
        if dims.len() == 4 && dims[1] == 3 {
            output_name = Some(outlet.name().to_string());
            output_layout = Some(TensorLayout::Nchw);
            output_channels = Some(3);
            break;
        }
        if dims.len() == 4 && dims[3] == 3 {
            output_name = Some(outlet.name().to_string());
            output_layout = Some(TensorLayout::Nhwc);
            output_channels = Some(3);
            break;
        }
        if dims.len() == 4 && symbol_is_hw_nchw(dimension_symbols) {
            output_name = Some(outlet.name().to_string());
            output_layout = Some(TensorLayout::Nchw);
            output_channels = if dims[1] > 1 { Some(dims[1]) } else { None };
            break;
        }
        if dims.len() == 4 && symbol_is_hw_nhwc(dimension_symbols) {
            output_name = Some(outlet.name().to_string());
            output_layout = Some(TensorLayout::Nhwc);
            output_channels = if dims[3] > 1 { Some(dims[3]) } else { None };
            break;
        }
    }

    let output_name = output_name.ok_or_else(|| {
        CoreError::UnsupportedModel("no RGB output tensor detected".to_string())
    })?;

    Ok(ModelSignature {
        image_inputs,
        timestep_input,
        output_name,
        output_layout: output_layout.expect("output layout should exist when output name exists"),
        output_channels,
    })
}

fn symbol_is_hw_nchw(symbols: &[String]) -> bool {
    symbols.len() == 4 && symbols[2] == "h" && symbols[3] == "w"
}

fn symbol_is_hw_nhwc(symbols: &[String]) -> bool {
    symbols.len() == 4 && symbols[1] == "h" && symbols[2] == "w"
}

fn log_model_signature(session: &Session, signature: &ModelSignature) {
    for (index, input) in session.inputs().iter().enumerate() {
        println!(
            "progress model-input[{index}] name={} dtype={:?}",
            input.name(),
            input.dtype()
        );
    }
    for (index, output) in session.outputs().iter().enumerate() {
        println!(
            "progress model-output[{index}] name={} dtype={:?}",
            output.name(),
            output.dtype()
        );
    }
    println!(
        "progress signature images={} timestep={} output={} layout={:?} channels={:?}",
        signature.image_inputs.len(),
        signature
            .timestep_input
            .as_ref()
            .map(|s| s.name.as_str())
            .unwrap_or("<none>"),
        signature.output_name,
        signature.output_layout,
        signature.output_channels
    );
}

fn concrete_dims(shape: impl IntoIterator<Item = i64>) -> Vec<usize> {
    shape
        .into_iter()
        .map(|d| {
            if d <= 0 {
                1usize
            } else {
                usize::try_from(d).unwrap_or(1)
            }
        })
        .collect()
}

fn align_dimension(size: usize, block: usize) -> usize {
    let remainder = size % block;
    if remainder == 0 {
        size
    } else {
        size + (block - remainder)
    }
}

fn pad_image(image: &RgbImage, width: u32, height: u32) -> RgbImage {
    if image.dimensions() == (width, height) {
        return image.clone();
    }

    let mut padded = ImageBuffer::new(width, height);
    for y in 0..height {
        let src_y = y.min(image.height() - 1);
        for x in 0..width {
            let src_x = x.min(image.width() - 1);
            padded.put_pixel(x, y, *image.get_pixel(src_x, src_y));
        }
    }
    padded
}

fn image_to_tensor(image: &RgbImage, layout: TensorLayout) -> Array4<f32> {
    let width = image.width() as usize;
    let height = image.height() as usize;
    match layout {
        TensorLayout::Nchw => {
            let mut array = Array4::<f32>::zeros((1, 3, height, width));
            for y in 0..height {
                for x in 0..width {
                    let pixel = image.get_pixel(x as u32, y as u32);
                    array[[0, 0, y, x]] = f32::from(pixel[0]) / 255.0;
                    array[[0, 1, y, x]] = f32::from(pixel[1]) / 255.0;
                    array[[0, 2, y, x]] = f32::from(pixel[2]) / 255.0;
                }
            }
            array
        }
        TensorLayout::Nhwc => {
            let mut array = Array4::<f32>::zeros((1, height, width, 3));
            for y in 0..height {
                for x in 0..width {
                    let pixel = image.get_pixel(x as u32, y as u32);
                    array[[0, y, x, 0]] = f32::from(pixel[0]) / 255.0;
                    array[[0, y, x, 1]] = f32::from(pixel[1]) / 255.0;
                    array[[0, y, x, 2]] = f32::from(pixel[2]) / 255.0;
                }
            }
            array
        }
    }
}

fn concat_frames(left: &RgbImage, right: &RgbImage, layout: TensorLayout) -> Array4<f32> {
    let width = left.width() as usize;
    let height = left.height() as usize;
    match layout {
        TensorLayout::Nchw => {
            let mut array = Array4::<f32>::zeros((1, 6, height, width));
            for y in 0..height {
                for x in 0..width {
                    let a = left.get_pixel(x as u32, y as u32);
                    let b = right.get_pixel(x as u32, y as u32);
                    array[[0, 0, y, x]] = f32::from(a[0]) / 255.0;
                    array[[0, 1, y, x]] = f32::from(a[1]) / 255.0;
                    array[[0, 2, y, x]] = f32::from(a[2]) / 255.0;
                    array[[0, 3, y, x]] = f32::from(b[0]) / 255.0;
                    array[[0, 4, y, x]] = f32::from(b[1]) / 255.0;
                    array[[0, 5, y, x]] = f32::from(b[2]) / 255.0;
                }
            }
            array
        }
        TensorLayout::Nhwc => {
            let mut array = Array4::<f32>::zeros((1, height, width, 6));
            for y in 0..height {
                for x in 0..width {
                    let a = left.get_pixel(x as u32, y as u32);
                    let b = right.get_pixel(x as u32, y as u32);
                    array[[0, y, x, 0]] = f32::from(a[0]) / 255.0;
                    array[[0, y, x, 1]] = f32::from(a[1]) / 255.0;
                    array[[0, y, x, 2]] = f32::from(a[2]) / 255.0;
                    array[[0, y, x, 3]] = f32::from(b[0]) / 255.0;
                    array[[0, y, x, 4]] = f32::from(b[1]) / 255.0;
                    array[[0, y, x, 5]] = f32::from(b[2]) / 255.0;
                }
            }
            array
        }
    }
}

fn scalar_tensor(dims: &[usize], value: f32) -> ndarray::ArrayD<f32> {
    if dims.is_empty() {
        return ndarray::ArrayD::from_elem(ndarray::IxDyn(&[]), value);
    }

    let normalized: Vec<usize> = dims
        .iter()
        .copied()
        .map(|d| NonZeroUsize::new(d).map(usize::from).unwrap_or(1))
        .collect();
    ndarray::ArrayD::from_elem(ndarray::IxDyn(&normalized), value)
}

fn tensor_to_image(
    array: ndarray::ArrayViewD<'_, f32>,
    layout: TensorLayout,
    output_channels: Option<usize>,
    width: u32,
    height: u32,
) -> Result<RgbImage> {
    let shape = array.shape();
    let (expected_h, expected_w) = (height as usize, width as usize);
    let mut image = RgbImage::new(width, height);

    match layout {
        TensorLayout::Nchw => {
            if shape.len() != 4 || shape[0] != 1 || shape[2] < expected_h || shape[3] < expected_w {
                return Err(CoreError::InvalidOutput(format!(
                    "expected NCHW output with shape [1,C,>=H,>=W], got {:?}",
                    shape
                )));
            }
            if let Some(channels) = output_channels {
                if channels != shape[1] {
                    return Err(CoreError::InvalidOutput(format!(
                        "declared output channels {:?} but runtime shape is {:?}",
                        output_channels, shape
                    )));
                }
            }
            if shape[1] < 3 {
                return Err(CoreError::InvalidOutput(format!(
                    "NCHW output channel count < 3: {:?}",
                    shape
                )));
            }
            for y in 0..expected_h {
                for x in 0..expected_w {
                    let pixel = [
                        clamp_u8(array[[0, 0, y, x]]),
                        clamp_u8(array[[0, 1, y, x]]),
                        clamp_u8(array[[0, 2, y, x]]),
                    ];
                    image.put_pixel(x as u32, y as u32, Rgb(pixel));
                }
            }
        }
        TensorLayout::Nhwc => {
            if shape.len() != 4 || shape[0] != 1 || shape[1] < expected_h || shape[2] < expected_w {
                return Err(CoreError::InvalidOutput(format!(
                    "expected NHWC output with shape [1,>=H,>=W,C], got {:?}",
                    shape
                )));
            }
            if let Some(channels) = output_channels {
                if channels != shape[3] {
                    return Err(CoreError::InvalidOutput(format!(
                        "declared output channels {:?} but runtime shape is {:?}",
                        output_channels, shape
                    )));
                }
            }
            if shape[3] < 3 {
                return Err(CoreError::InvalidOutput(format!(
                    "NHWC output channel count < 3: {:?}",
                    shape
                )));
            }
            for y in 0..expected_h {
                for x in 0..expected_w {
                    let pixel = [
                        clamp_u8(array[[0, y, x, 0]]),
                        clamp_u8(array[[0, y, x, 1]]),
                        clamp_u8(array[[0, y, x, 2]]),
                    ];
                    image.put_pixel(x as u32, y as u32, Rgb(pixel));
                }
            }
        }
    }

    Ok(image)
}

fn clamp_u8(value: f32) -> u8 {
    (value.clamp(0.0, 1.0) * 255.0).round() as u8
}

fn run_parallel_interpolation_tasks(
    model_path: &Path,
    tasks: Vec<InterpolationTask>,
    total_output_frames: usize,
    configured_threads: usize,
    max_memory_mb: usize,
) -> Result<usize> {
    let task_count = tasks.len();
    if task_count == 0 {
        return Ok(0);
    }

    let worker_count =
        choose_interpolation_worker_count(task_count, configured_threads, max_memory_mb);
    println!("progress interpolate-workers={worker_count}");

    if worker_count <= 1 {
        let mut interpolator = RifeInterpolator::load(model_path)?;
        for (done_index, task) in tasks.into_iter().enumerate() {
            let left = load_rgb_image(&task.left_frame_path)?;
            let right = load_rgb_image(&task.right_frame_path)?;
            let image = interpolator.interpolate(&left, &right, task.t)?;
            image.save(&task.abs_output_path)?;
            println!(
                "progress interpolate {}/{} pair={}->{} t={:.6}",
                done_index + 1,
                total_output_frames,
                task.left_index,
                task.right_index,
                task.t
            );
        }
        return Ok(task_count);
    }

    let queue = Arc::new(Mutex::new(VecDeque::from(tasks)));
    let completed = Arc::new(AtomicUsize::new(0));
    let (tx, rx) = mpsc::channel::<std::result::Result<InterpolationResult, CoreError>>();

    let mut handles = Vec::with_capacity(worker_count);
    for worker_index in 0..worker_count {
        let queue = Arc::clone(&queue);
        let completed = Arc::clone(&completed);
        let tx = tx.clone();
        let model_path = model_path.to_path_buf();
        let handle = thread::Builder::new()
            .name(format!("mwc-rife-{worker_index}"))
            .spawn(move || {
            let mut interpolator = match RifeInterpolator::load(&model_path) {
                Ok(interpolator) => interpolator,
                Err(err) => {
                    let _ = tx.send(Err(err));
                    return;
                }
            };
            println!("progress interpolate-worker-started={worker_index}");

            loop {
                let task = match queue.lock() {
                    Ok(mut queue) => queue.pop_front(),
                    Err(_) => {
                        let _ = tx.send(Err(CoreError::InvalidOutput(
                            "interpolation queue poisoned".to_string(),
                        )));
                        return;
                    }
                };

                let Some(task) = task else {
                    break;
                };

                let result = (|| -> Result<InterpolationResult> {
                    let left = load_rgb_image(&task.left_frame_path)?;
                    let right = load_rgb_image(&task.right_frame_path)?;
                    let image = interpolator.interpolate(&left, &right, task.t)?;
                    image.save(&task.abs_output_path)?;
                    Ok(InterpolationResult {
                        output_index: task.output_index,
                        rel_output_path: task.rel_output_path,
                    })
                })();

                match result {
                    Ok(done) => {
                        let done_count = completed.fetch_add(1, Ordering::SeqCst) + 1;
                        if done_count == task_count || done_count % 8 == 0 {
                            println!("progress interpolate {done_count}/{total_output_frames}");
                        }
                        let _ = tx.send(Ok(done));
                    }
                    Err(err) => {
                        let _ = tx.send(Err(err));
                        return;
                    }
                }
            }
        })
        .map_err(CoreError::Io)?;
        handles.push(handle);
    }
    drop(tx);

    let mut results = HashMap::<usize, PathBuf>::with_capacity(task_count);
    let mut first_error: Option<CoreError> = None;
    for received in rx {
        match received {
            Ok(done) => {
                results.insert(done.output_index, done.rel_output_path);
            }
            Err(err) => {
                if first_error.is_none() {
                    first_error = Some(err);
                }
            }
        }
    }

    for handle in handles {
        if handle.join().is_err() && first_error.is_none() {
            first_error = Some(CoreError::InvalidOutput(
                "interpolation worker thread panicked".to_string(),
            ));
        }
    }

    if let Some(err) = first_error {
        return Err(err);
    }
    if results.len() != task_count {
        return Err(CoreError::InvalidOutput(format!(
            "interpolation completed {} tasks but expected {}",
            results.len(),
            task_count
        )));
    }

    Ok(task_count)
}

fn choose_interpolation_worker_count(
    task_count: usize,
    configured_threads: usize,
    max_memory_mb: usize,
) -> usize {
    let detected = std::thread::available_parallelism()
        .map(usize::from)
        .unwrap_or(1);
    let cpu_cap = detected.saturating_sub(1).max(1);
    let auto_target = auto_worker_target(detected);
    let requested = if configured_threads == 0 {
        auto_target
    } else {
        configured_threads.clamp(1, 256)
    };
    let memory_cap = choose_memory_worker_cap(max_memory_mb).unwrap_or(cpu_cap).max(1);
    let chosen = requested
        .min(cpu_cap)
        .min(memory_cap)
        .min(task_count.max(1))
        .clamp(1, 256);
    println!(
        "progress interpolate-scheduler detected={detected} requested={requested} cpu_cap={cpu_cap} memory_cap={memory_cap} max_memory_mb={max_memory_mb} chosen={chosen}"
    );
    chosen
}

fn auto_worker_target(detected: usize) -> usize {
    if detected <= 2 {
        1
    } else {
        ((detected * 3) / 4).clamp(1, detected.saturating_sub(1).max(1))
    }
}

fn choose_memory_worker_cap(max_memory_mb: usize) -> Option<usize> {
    let requested_budget_kib = max_memory_mb.max(256).saturating_mul(1024);
    let budget_kib = match read_mem_available_kib() {
        Some(mem_available_kib) => {
            let reserve_kib = MEMORY_RESERVE_KIB.max(mem_available_kib / 4);
            requested_budget_kib.min(mem_available_kib.saturating_sub(reserve_kib).max(256 * 1024))
        }
        None => requested_budget_kib,
    };
    let cap = (budget_kib / ESTIMATED_WORKER_MEMORY_KIB).max(1);
    Some(cap)
}

fn read_mem_available_kib() -> Option<usize> {
    let content = fs::read_to_string("/proc/meminfo").ok()?;
    for line in content.lines() {
        let Some(rest) = line.strip_prefix("MemAvailable:") else {
            continue;
        };
        let value = rest
            .split_whitespace()
            .next()
            .and_then(|it| it.parse::<usize>().ok())?;
        return Some(value);
    }
    None
}
