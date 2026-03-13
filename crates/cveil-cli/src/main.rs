use clap::Parser;
use tracing_subscriber::{EnvFilter, fmt};

use cveil_cli::cli;
use cveil_cli::client;
use cveil_cli::config;
use cveil_cli::daemon;

#[tokio::main]
async fn main() {
    let args = cli::Cli::parse();

    let log_level = match &args.command {
        // Daemon mode uses configured log level
        cli::Command::Start(start) => start.log_level.as_deref().unwrap_or("info"),
        // Client commands: quiet unless CVEIL_LOG env is set
        _ => "warn",
    };

    fmt()
        .with_env_filter(
            EnvFilter::try_from_env("CVEIL_LOG")
                .unwrap_or_else(|_| EnvFilter::new(log_level)),
        )
        .with_target(false)
        .init();

    let exit_code = match args.command {
        cli::Command::Start(start) => daemon::run_daemon(start).await,
        cli::Command::Stop(stop) => client::run_stop(stop).await,
        cli::Command::Status(status) => client::run_status(status).await,
        cli::Command::Mask(mask) => client::run_mask(mask).await,
        cli::Command::MaskBatch(batch) => client::run_mask_batch(batch).await,
        cli::Command::Evaluate(eval) => client::run_evaluate(eval).await,
        cli::Command::Config(cfg) => config::run_config_show(cfg),
    };

    std::process::exit(exit_code);
}
