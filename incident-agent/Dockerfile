FROM rust:1.99.1-slim-bookworm@sha256:f2c004e3d2d8b547e8cf7ffbc29205385f4e71d23e53e4e383065db5e9f822cd AS build
WORKDIR /src
COPY --exclude=knowledge . .
RUN cargo build --locked --release -p agent

FROM debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
COPY --from=build /src/target/release/agent /usr/local/bin/agent
RUN mkdir -m 0755 /knowledge && mkdir /data && chown 10001:10001 /data
COPY --chmod=0444 knowledge/*.md /knowledge/
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/agent"]
