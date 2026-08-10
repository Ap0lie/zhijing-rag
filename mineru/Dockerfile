FROM vllm/vllm-openai:v0.21.0@sha256:4ac9b7c6dabc3ec762c0edef4e9245abe98373844da91cc53ee42e5c58280c5b

ARG MINERU_VERSION=3.4.4
ARG MINERU_RELEASE_COMMIT=0dfc9460cd9ab693b9af60ae3fbffd7bc111b062
ARG MINERU_WHEEL_SHA256=d4d678539782a7683d998e2914a52d96b5720676ce65658b29666b1f4d9dfd13
ARG MINERU_MODEL_REVISION=bff20d4ae2bf202df9f45284b4d43681555a97ed
ARG MINERU_MODEL_MANIFEST_SHA256=16981dc38075623ddec4fdcf7f055c89688f44a92076f534919f465be46c82e7

LABEL org.opencontainers.image.source="https://github.com/opendatalab/MinerU" \
      org.opencontainers.image.version="${MINERU_VERSION}" \
      org.opencontainers.image.revision="${MINERU_RELEASE_COMMIT}" \
      io.rag.mineru.model-revision="${MINERU_MODEL_REVISION}" \
      io.rag.mineru.model-manifest-sha256="${MINERU_MODEL_MANIFEST_SHA256}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        fontconfig \
        fonts-noto-cjk \
        fonts-noto-core \
        libgl1 \
    && rm -rf /var/lib/apt/lists/*

RUN MINERU_WHEEL="/tmp/mineru-${MINERU_VERSION}-py3-none-any.whl" \
    && curl -fsSL \
        -o "${MINERU_WHEEL}" \
        https://files.pythonhosted.org/packages/ec/d1/fd23b40d7bbdeaa04a6070ffeb21caff91c2c5a2c5fde22c7ce804f08dd8/mineru-3.4.4-py3-none-any.whl \
    && echo "${MINERU_WHEEL_SHA256}  ${MINERU_WHEEL}" | sha256sum -c - \
    && python3 -m pip install --no-cache-dir "${MINERU_WHEEL}" --break-system-packages \
    && python3 -m pip install --no-cache-dir "mineru[core]==${MINERU_VERSION}" --break-system-packages \
    && rm "${MINERU_WHEEL}"

RUN groupadd --gid 10001 mineru \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/mineru mineru \
    && install -d -o 10001 -g 10001 \
        /opt/mineru \
        /opt/mineru/models \
        /opt/mineru/models/vlm

USER 10001:10001

RUN MINERU_MODEL_REVISION="${MINERU_MODEL_REVISION}" \
    HF_HOME=/tmp/huggingface \
    python3 -c \
    "import json,os; from huggingface_hub import snapshot_download; root=snapshot_download('opendatalab/MinerU2.5-Pro-2605-1.2B', revision=os.environ['MINERU_MODEL_REVISION'], local_dir='/opt/mineru/models/vlm'); json.dump({'models-dir': {'vlm': root, 'pipeline': ''}, 'model-source': 'huggingface', 'config_version': '1.3.2'}, open('/opt/mineru/mineru.json','w'), ensure_ascii=False, indent=2)" \
    && rm -rf /tmp/huggingface /opt/mineru/models/vlm/.cache \
    && ACTUAL_MODEL_MANIFEST_SHA256="$( \
        cd /opt/mineru/models/vlm \
        && find . -type f -printf '%P\0' \
        | LC_ALL=C sort -z \
        | xargs -0 -r sha256sum \
        | sha256sum \
        | cut -d ' ' -f 1 \
    )" \
    && test "${ACTUAL_MODEL_MANIFEST_SHA256}" = "${MINERU_MODEL_MANIFEST_SHA256}" \
    && printf '%s\n' "${ACTUAL_MODEL_MANIFEST_SHA256}" \
        > /opt/mineru/model-manifest.sha256

ENV MINERU_TOOLS_CONFIG_JSON=/opt/mineru/mineru.json \
    MINERU_MODEL_SOURCE=local \
    MINERU_API_MAX_CONCURRENT_REQUESTS=1 \
    MINERU_API_OUTPUT_ROOT=/tmp/mineru-output \
    MINERU_API_ENABLE_FASTAPI_DOCS=0 \
    XDG_CACHE_HOME=/tmp/cache

ENTRYPOINT ["mineru-api"]
CMD ["--host", "0.0.0.0", "--port", "8000"]
