use serde::Deserialize;
use std::io::{self, Read, Write};
use std::path::PathBuf;
use wallpaper_core::{process_session, CoreConfig, ExportFormat};

#[derive(Debug, Deserialize)]
struct Request {
    session_dir: PathBuf,
    target_fps: u32,
    playback_fps: u32,
    export_format: String,
    rife_threads: usize,
    max_memory_mb: usize,
    auto_blend_loop: bool,
    blend_frame_count: usize,
}

fn main() {
    if let Err(err) = run() {
        emit_line(&format!("error {err}"));
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .map_err(|e| e.to_string())?;

    let req: Request = parse_kv_request(&input)?;
    let config = CoreConfig {
        target_fps: req.target_fps,
        playback_fps: req.playback_fps,
        export_format: req
            .export_format
            .parse::<ExportFormat>()
            .map_err(|e| e.to_string())?,
        rife_threads: req.rife_threads,
        max_memory_mb: req.max_memory_mb,
        auto_blend_loop: req.auto_blend_loop,
        blend_frame_count: req.blend_frame_count,
    };

    emit_line("progress core-start");
    let result = process_session(&req.session_dir, &config).map_err(|e| e.to_string())?;
    emit_line(&format!(
        "done playlist={} loop_info={} script={} script_bat={} mkv={} mkv_script={} mkv_script_bat={} transition_frames={} base_frames={} loop_start_frame={} selection_score={:.6}",
        result.playlist.to_string_lossy(),
        result.loop_info.to_string_lossy(),
        result.script.to_string_lossy(),
        result.script_bat.to_string_lossy(),
        result
            .mkv
            .as_ref()
            .map(|it| it.to_string_lossy().into_owned())
            .unwrap_or_default(),
        result.mkv_script.to_string_lossy(),
        result.mkv_script_bat.to_string_lossy(),
        result.transition_frames,
        result.base_frames,
        result.loop_start_frame,
        result.selection_score
    ));
    Ok(())
}

fn emit_line(line: &str) {
    let mut stdout = io::stdout().lock();
    let _ = writeln!(stdout, "{line}");
    let _ = stdout.flush();
}

fn parse_kv_request(input: &str) -> Result<Request, String> {
    let mut session_dir = None;
    let mut target_fps = None;
    let mut playback_fps = None;
    let mut export_format = None;
    let mut rife_threads = None;
    let mut max_memory_mb = None;
    let mut auto_blend_loop = None;
    let mut blend_frame_count = None;

    for line in input.lines().map(str::trim).filter(|l| !l.is_empty()) {
        let (key, value) = line
            .split_once('=')
            .ok_or_else(|| format!("invalid line: {line}"))?;
        match key {
            "session_dir" => session_dir = Some(PathBuf::from(value)),
            "target_fps" => target_fps = Some(value.parse::<u32>().map_err(|e| e.to_string())?),
            "playback_fps" => playback_fps = Some(value.parse::<u32>().map_err(|e| e.to_string())?),
            "export_format" => export_format = Some(value.to_string()),
            "rife_threads" => rife_threads = Some(value.parse::<usize>().map_err(|e| e.to_string())?),
            "max_memory_mb" => max_memory_mb = Some(value.parse::<usize>().map_err(|e| e.to_string())?),
            "auto_blend_loop" => auto_blend_loop = Some(value.parse::<bool>().map_err(|e| e.to_string())?),
            "blend_frame_count" => blend_frame_count = Some(value.parse::<usize>().map_err(|e| e.to_string())?),
            _ => {}
        }
    }

    Ok(Request {
        session_dir: session_dir.ok_or_else(|| "missing session_dir".to_string())?,
        target_fps: target_fps.ok_or_else(|| "missing target_fps".to_string())?,
        playback_fps: playback_fps.unwrap_or_else(|| target_fps.unwrap_or(24).max(24)),
        export_format: export_format.unwrap_or_else(|| "MKV_H264".to_string()),
        rife_threads: rife_threads.unwrap_or(0),
        max_memory_mb: max_memory_mb.unwrap_or(2048),
        auto_blend_loop: auto_blend_loop.ok_or_else(|| "missing auto_blend_loop".to_string())?,
        blend_frame_count: blend_frame_count.ok_or_else(|| "missing blend_frame_count".to_string())?,
    })
}
