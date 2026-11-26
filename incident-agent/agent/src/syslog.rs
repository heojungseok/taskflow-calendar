use std::net::UdpSocket;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::rfc3339::month_day;

const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];
const MAX_MESSAGE_CHARS: usize = 2000;

static SINK: OnceLock<Syslog> = OnceLock::new();

struct Syslog {
    socket: UdpSocket,
    target: String,
}

impl Syslog {
    fn connect(addr: &str) -> std::io::Result<Self> {
        let valid_port = addr
            .rsplit_once(':')
            .is_some_and(|(_, port)| port.parse::<u16>().is_ok());
        if !valid_port {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "syslog address needs host:port",
            ));
        }
        Ok(Self {
            socket: UdpSocket::bind("0.0.0.0:0")?,
            target: addr.to_owned(),
        })
    }

    fn send(&self, unix: u64, message: &str) {
        let _ = self
            .socket
            .send_to(format_line(unix, message).as_bytes(), self.target.as_str());
    }
}

fn format_line(unix: u64, message: &str) -> String {
    let (month, day) = month_day(unix / 86_400);
    let seconds = unix % 86_400;
    let message: String = message
        .chars()
        .take(MAX_MESSAGE_CHARS)
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    format!(
        "<12>{} {day:2} {:02}:{:02}:{:02} incident-agent: {message}",
        MONTHS[month as usize - 1],
        seconds / 3600,
        seconds % 3600 / 60,
        seconds % 60
    )
}

pub(crate) fn init(addr: &str) -> std::io::Result<()> {
    let _ = SINK.set(Syslog::connect(addr)?);
    Ok(())
}

pub(crate) fn line(message: &str) {
    eprintln!("{message}");
    if let Some(sink) = SINK.get() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        sink.send(now, message);
    }
}

#[cfg(test)]
mod tests;
