use std::net::UdpSocket;
use std::time::Duration;

use super::*;

const NOV_26_063842: u64 = 1_764_139_122;
const NOV_06_000000: u64 = 1_762_387_200;

#[test]
fn t44_nginx와_같은_rfc3164_줄로_만들고_줄바꿈은_공백으로_바꾼다() {
    assert_eq!(
        format_line(NOV_26_063842, "evidence loki failed\nL2: x\r\ty"),
        "<12>Nov 26 06:38:42 incident-agent: evidence loki failed L2: x  y"
    );
}

#[test]
fn t44_한_자리_날짜는_공백으로_채운다() {
    assert_eq!(
        format_line(NOV_06_000000, "x"),
        "<12>Nov  6 00:00:00 incident-agent: x"
    );
}

#[test]
fn t44_설정한_주소로_udp_한_줄을_보낸다() {
    let receiver = UdpSocket::bind("127.0.0.1:0").unwrap();
    receiver
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let sink = Syslog::connect(&receiver.local_addr().unwrap().to_string()).unwrap();
    sink.send(NOV_26_063842, "model failed");
    let mut buffer = [0_u8; 512];
    let size = receiver.recv(&mut buffer).unwrap();
    assert_eq!(
        std::str::from_utf8(&buffer[..size]).unwrap(),
        "<12>Nov 26 06:38:42 incident-agent: model failed"
    );
}

#[test]
fn t44_찾을_수_없는_주소는_연결_오류다() {
    assert!(Syslog::connect("no-such-host.invalid:5140").is_err());
    assert!(Syslog::connect("missing-port").is_err());
}

#[test]
fn t44_긴_메시지는_2000자에서_자른다() {
    let line = format_line(NOV_26_063842, &"가".repeat(2_500));
    let message = line.split_once("incident-agent: ").unwrap().1;
    assert_eq!(message.chars().count(), 2_000);
}
