FROM python:3.13-slim@sha256:ae14d4ce8771606477a81446e7ef429edd6762305fc50c84507c1041fa8285d1
ARG TARGETARCH
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.lock .
RUN --mount=type=bind,source=wheels,target=/wheels,readonly \
    pip install --no-cache-dir --no-index --only-binary=:all: --require-hashes \
    --find-links "/wheels/linux-$TARGETARCH" -r requirements.lock \
    && groupadd --gid 10001 oasis \
    && useradd --uid 10001 --gid 10001 --no-create-home oasis \
    && install -d -o oasis -g oasis -m 0700 /var/lib/oasis \
    && install -o oasis -g oasis -m 0600 /dev/null /var/lib/oasis/.volume
COPY bank.py admin.py protocol.py schema.sql test_bank.py test_admin.py ./
USER 10001:10001
ENTRYPOINT ["python", "/app/bank.py", "--state", "/var/lib/oasis/keys"]
CMD ["serve"]
