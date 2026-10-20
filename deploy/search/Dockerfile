# Elastic 维护的 Docker Official Image 8.19.23；固定多架构 digest，插件由同版本安装器校验。
FROM docker.io/library/elasticsearch@sha256:3f4d63c59ca5a05b535179dfcced47a514119e351fa81653f69902beacae90c9
USER root
RUN mkdir -p /usr/share/elasticsearch/snapshots \
    && chown elasticsearch:root /usr/share/elasticsearch/snapshots
USER elasticsearch
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
