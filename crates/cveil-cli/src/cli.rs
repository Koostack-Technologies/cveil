use clap::{Parser, Subcommand};

/// cveil — consent masking daemon and CLI client
#[derive(Parser, Debug)]
#[command(name = "cveil", version, about)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand, Debug)]
pub enum Command {
    /// Start the consent masking daemon
    Start(StartArgs),
    /// Gracefully stop the running daemon
    Stop(SocketArgs),
    /// Show daemon health, cache stats, and SSE state
    Status(SocketArgs),
    /// Mask a JSON object via the daemon
    Mask(MaskArgs),
    /// Mask an array of JSON objects via the daemon
    MaskBatch(MaskBatchArgs),
    /// Evaluate per-field masking types without applying masks
    Evaluate(EvaluateArgs),
    /// Show resolved configuration (no daemon required)
    Config(ConfigArgs),
}

#[derive(clap::Args, Debug)]
pub struct StartArgs {
    /// Config file path
    #[arg(long, default_value = "~/.cveil/config.json")]
    pub config: String,

    /// Unix domain socket path
    #[arg(long)]
    pub socket: Option<String>,

    /// Fork to background and write PID file
    #[arg(long)]
    pub daemonize: bool,

    /// PID file path (only used with --daemonize)
    #[arg(long, default_value = "/tmp/cveil.pid")]
    pub pid_file: String,

    /// Log level: trace, debug, info, warn, error
    #[arg(long)]
    pub log_level: Option<String>,
}

#[derive(clap::Args, Debug)]
pub struct SocketArgs {
    /// Unix domain socket path
    #[arg(long, default_value = "/tmp/cveil.sock")]
    pub socket: String,
}

#[derive(clap::Args, Debug)]
pub struct MaskArgs {
    /// Data subject ID
    #[arg(long)]
    pub subject: String,

    /// Entity type (e.g., "User")
    #[arg(long)]
    pub entity: String,

    /// Processing purpose (e.g., "Marketing")
    #[arg(long)]
    pub purpose: String,

    /// Jurisdiction override (uses daemon default if omitted)
    #[arg(long)]
    pub jurisdiction: Option<String>,

    /// JSON input: file path, `-` for stdin, or inline JSON string
    #[arg(long, default_value = "-")]
    pub input: String,

    /// Unix domain socket path
    #[arg(long, default_value = "/tmp/cveil.sock")]
    pub socket: String,

    /// Pretty-print the output
    #[arg(long)]
    pub pretty: bool,
}

#[derive(clap::Args, Debug)]
pub struct MaskBatchArgs {
    /// Data subject ID
    #[arg(long)]
    pub subject: String,

    /// Entity type
    #[arg(long)]
    pub entity: String,

    /// Processing purpose
    #[arg(long)]
    pub purpose: String,

    /// Jurisdiction override
    #[arg(long)]
    pub jurisdiction: Option<String>,

    /// JSON array input: file path, `-` for stdin, or inline JSON string
    #[arg(long, default_value = "-")]
    pub input: String,

    /// Unix domain socket path
    #[arg(long, default_value = "/tmp/cveil.sock")]
    pub socket: String,

    /// Pretty-print the output
    #[arg(long)]
    pub pretty: bool,
}

#[derive(clap::Args, Debug)]
pub struct EvaluateArgs {
    /// Data subject ID
    #[arg(long)]
    pub subject: String,

    /// Entity type
    #[arg(long)]
    pub entity: String,

    /// Processing purpose
    #[arg(long)]
    pub purpose: String,

    /// Comma-separated field names
    #[arg(long, value_delimiter = ',')]
    pub fields: Vec<String>,

    /// Unix domain socket path
    #[arg(long, default_value = "/tmp/cveil.sock")]
    pub socket: String,

    /// Pretty-print the output
    #[arg(long)]
    pub pretty: bool,
}

#[derive(clap::Args, Debug)]
pub struct ConfigArgs {
    /// Config file path
    #[arg(long, default_value = "~/.cveil/config.json")]
    pub config: String,
}
