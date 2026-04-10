FROM debian:bookworm-slim

WORKDIR /srv/piper-api

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIPER_BIN=/usr/bin/piper
ENV PIPER_MODELS_DIR=/models
ENV PIPER_DEFAULT_VOICE=

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        python3 \
        python3-pip \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

COPY app.py .

EXPOSE 8090

CMD ["uvicorn", "app:APP", "--host", "0.0.0.0", "--port", "8090"]
