# OCR单独构建；原Java镜像及默认部署不增加原生解析依赖。
FROM eclipse-temurin:21.0.12_8-jre-ubi9-minimal@sha256:fa6a3cd1e88402446002f86e0d06f5202d7d6ec02d525888f9c0ea2eeb5b07ad AS engine
RUN microdnf install -y gcc-c++ make autoconf automake libtool pkgconf-pkg-config libpng-devel zlib-devel tar gzip && microdnf clean all
WORKDIR /build
ADD --checksum=sha256:c73363397f96eb1295602bf44d708a994ad42046c791bf03ea0505d829bdb6a7 https://github.com/DanBloomberg/leptonica/releases/download/1.87.0/leptonica-1.87.0.tar.gz /build/leptonica.tar.gz
ADD --checksum=sha256:a8dbf8b8c8ee427ff6ad1d99116502feaf59440af1adb893caf60a6576826cf4 https://github.com/tesseract-ocr/tesseract/archive/refs/tags/5.5.4.tar.gz /build/tesseract.tar.gz
RUN tar xzf leptonica.tar.gz && cd leptonica-1.87.0 && \
    ./configure --prefix=/opt/ocr --disable-static --without-jpeg --disable-programs --without-libtiff --without-libwebp --without-libopenjpeg && \
    make -j2 && make install
ENV PKG_CONFIG_PATH=/opt/ocr/lib/pkgconfig LD_LIBRARY_PATH=/opt/ocr/lib
RUN tar xzf tesseract.tar.gz && cd tesseract-5.5.4 && ./autogen.sh && \
    ./configure --prefix=/opt/ocr --disable-static --disable-openmp --disable-graphics --without-curl --without-archive && \
    make -j2 && make install && \
    mkdir -p /opt/ocr/tessdata && \
    cp -L /usr/lib64/libpng16.so.16 /opt/ocr/lib/ && \
    /opt/ocr/bin/tesseract --version
ADD --checksum=sha256:7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata /opt/ocr/tessdata/eng.traineddata
ADD --checksum=sha256:a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730 https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/chi_sim.traineddata /opt/ocr/tessdata/chi_sim.traineddata
RUN chmod -R a+rX /opt/ocr && mkdir -p /opt/ocr/licenses && \
    cp /build/tesseract-5.5.4/LICENSE /opt/ocr/licenses/tesseract && \
    cp /build/leptonica-1.87.0/leptonica-license.txt /opt/ocr/licenses/leptonica && \
    cp /usr/share/licenses/libpng/LICENSE /opt/ocr/licenses/libpng

FROM eclipse-temurin:21.0.12_8-jre-ubi9-minimal@sha256:fa6a3cd1e88402446002f86e0d06f5202d7d6ec02d525888f9c0ea2eeb5b07ad
ARG SOURCE_FINGERPRINT
LABEL org.opencontainers.image.source="https://github.com/lirji/agentic-ai-platform" \
      io.agentic.source-fingerprint="${SOURCE_FINGERPRINT}"
COPY --from=engine /opt/ocr /opt/ocr
ENV LD_LIBRARY_PATH=/opt/ocr/lib KNOWLEDGE_OCR_EXECUTABLE=/opt/ocr/bin/tesseract KNOWLEDGE_OCR_DATA=/opt/ocr/tessdata
WORKDIR /app
COPY jars/knowledge-service.jar /app/service.jar
COPY jars/knowledge-parser.jar /app/knowledge-parser.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar", "/app/service.jar"]
