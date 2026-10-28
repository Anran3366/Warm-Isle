FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai \
    PIP_NO_CACHE_DIR=1

# libgl/libglib：rapidocr(OpenCV) 依赖
# 国内镜像加速（Debian apt）
RUN if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
      sed -i 's|deb.debian.org|mirrors.tencentyun.com|g' /etc/apt/sources.list.d/debian.sources; \
    fi; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|deb.debian.org|mirrors.tencentyun.com|g' /etc/apt/sources.list; \
    fi

RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY deploy/linux/guard/requirements.txt /app/requirements.txt
# PyPI 国内镜像
RUN pip install -i http://mirrors.tencentyun.com/pypi/simple --trusted-host mirrors.tencentyun.com --upgrade pip \
 && pip install -i http://mirrors.tencentyun.com/pypi/simple --trusted-host mirrors.tencentyun.com -r /app/requirements.txt

COPY guard/app.py /app/app.py

EXPOSE 8081
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8081"]
