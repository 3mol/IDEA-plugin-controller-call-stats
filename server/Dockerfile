FROM python:3.11-slim

LABEL maintainer="prod-call-stats"

WORKDIR /app

# 仅 MySQL 模式需要第三方依赖；csv 模式仅使用标准库
RUN pip install --no-cache-dir pymysql

# 拷贝服务代码与静态资源
COPY server.py datasource.py db_source.py csv_source.py ./
COPY web ./web

# 默认监听端口
ENV PORT=8089 \
    PYTHONUNBUFFERED=1

EXPOSE 8089

CMD ["python", "server.py"]