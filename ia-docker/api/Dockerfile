# API de inspeção (refinamento + TFLite). Também usada pelo serviço "refinador" (mesma imagem).
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_PATH=/modelos/modelo_producao.tflite \
    REFINAR=true \
    DADOS_DIR=/dados

RUN apt-get update \
 && apt-get install -y --no-install-recommends libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

# E2: o modelo NAO e embutido na imagem; vem do volume ./modelos (modelo_producao.tflite validado)
COPY main.py refinador_tampinhas.py ./

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
