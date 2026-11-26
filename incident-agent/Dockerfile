FROM rust:1.99.1-slim-bookworm@sha256:f2c004e3d2d8b547e8cf7ffbc29205385f4e71d23e53e4e383065db5e9f822cd AS build
WORKDIR /src
COPY . .
RUN cargo build --locked --release -p agent

FROM debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin agent
COPY --from=build /src/target/release/agent /usr/local/bin/agent
COPY knowledge/*.md /knowledge/
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/agent"]
