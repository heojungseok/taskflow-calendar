use std::net::{SocketAddr, ToSocketAddrs, UdpSocket};
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::rfc3339::month_day;

const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];
const MAX_MESSAGE_CHARS: usize = 2000;

static SINK: OnceLock<Syslog> = OnceLock::new();

pub(crate) struct Syslog {
    socket: UdpSocket,
    target: SocketAddr,
}

impl Syslog {
    pub(crate) fn connect(addr: &str) -> std::io::Result<Self> {
        let target = addr.to_socket_addrs()?.next().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::NotFound, "syslog address not found")
        })?;
        let local = if target.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        Ok(Self {
            socket: UdpSocket::bind(local)?,
            target,
        })
    }

    pub(crate) fn send(&self, unix: u64, message: &str) {
        let _ = self
            .socket
            .send_to(format_line(unix, message).as_bytes(), self.target);
    }
}

pub(crate) fn format_line(unix: u64, message: &str) -> String {
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
