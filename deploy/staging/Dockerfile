# staging 专用镜像：直接拷预构建的 jar，不在服务器上编译
# （服务器只有 2C/3.6G，编译一次会跟生产抢内存）
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 国内镜像加速 + ffmpeg（语音转码/元数据剥离要用）
RUN if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.tencentyun.com|g; s|security.ubuntu.com|mirrors.tencentyun.com|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.tencentyun.com|g; s|security.ubuntu.com|mirrors.tencentyun.com|g' /etc/apt/sources.list; \
    fi

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY qqbot.jar /app/app.jar

# 比生产小一档：staging 只服务测试号
ENV JAVA_OPTS="-Xms192m -Xmx384m -Dfile.encoding=UTF-8"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=8080"]
