# IA de inspeção (ia-docker)

Referência da parte de IA. O passo a passo para subir o sistema inteiro está em `COMO-RODAR-O-SISTEMA.md` (seções 5 e 14).

## Estrutura

```
ia-docker\
  docker-compose.yml       serviços: treino, api e refinador
  dados\                   fotos de origem: aprovada\ e reprovada\; *_refinada\ (gerado pelo refinador)
  dados_teste\             fotos separadas para avaliar o modelo (não entram no treino; criar à mão)
  modelos\                 candidato.tflite, modelo_producao.tflite, metricas_treino.json
  api\                     API FastAPI (porta 8000) e refinador de imagens
  treinamento\             script de treino, portão de qualidade, avaliação e limpeza do dataset
```

O modelo **não** vem embutido na imagem. A API lê `modelos\modelo_producao.tflite` pelo volume e recusa subir sem ele.

## Serviços

| Serviço | O que faz | Quando roda |
|---|---|---|
| `treino` | Refina as fotos novas e treina um **candidato**. Só promove a produção se passar no portão (acurácia ≥ 85% e recall mínimo ≥ 75% por classe) | Sob demanda, com `TREINAR_AO_SUBIR=true`. Com `false` (padrão do compose) termina sem treinar |
| `api` | Classifica imagens com `modelo_producao.tflite` | Sempre, depois que `treino` termina com sucesso |
| `refinador` | Refina as fotos que chegarem em `dados\aprovada\` e `dados\reprovada\` | Sempre |

## Endpoints da API

| Método | Rota | Descrição |
|---|---|---|
| GET | `/health` | `status`, `model_loaded`, `modelo` (caminho), `refinamento` |
| POST | `/predict` | Multipart com campo `file`. Devolve a classe e a confiança |
| POST | `/predict/base64` | Mesma função, com a imagem em base64 (não é usado pelo backend atual) |

Documentação interativa: `http://localhost:8000/docs`.

Teste de uma foto:

```bat
curl.exe -F "file=@C:\caminho\foto.png" http://localhost:8000/predict
```

## Variáveis de ambiente

| Variável | Onde | Padrão | Para quê |
|---|---|---|---|
| `MODEL_PATH` | `api` | `/modelos/modelo_producao.tflite` | Modelo que a API carrega |
| `REFINAR` | `api` | `true` | `false` desliga o refinamento (para comparar) |
| `TREINAR_AO_SUBIR` | `treino` | `true` no script, `false` no compose | Liga o treino ao subir o serviço |
| `EPOCAS`, `MODELO_SAIDA`, `MODELO_PRODUCAO` | `treino` | `25`, `/modelos/candidato.tflite`, `/modelos/modelo_producao.tflite` | Treino e destino dos modelos |
| `PORTAO_ACURACIA_MINIMA`, `PORTAO_RECALL_MINIMO` | `treino` | `0.85`, `0.75` | Critérios para promover o candidato |

## Treinar de novo com mais fotos

1. Adicione fotos em `dados\aprovada\` e `dados\reprovada\` e espere o refinador processar (aparecem em `*_refinada\`).
2. Apague `modelos\candidato.tflite` e `modelos\metricas_treino.json`. Sem isso o treino diz "Modelo ja treinado com estas imagens".
3. Rode:

```bat
docker compose build treino
docker compose run --rm -e TREINAR_AO_SUBIR=true treino
docker compose up -d --build api
```

Se o portão reprovar o candidato, o `modelo_producao.tflite` atual não muda. Veja `modelos\metricas_treino.json`.

## Avaliar o modelo com fotos que não treinaram

```bat
cd treinamento
py -3.11 limpar_dataset.py --dados ..\dados
py -3.11 avaliar_modelo.py --modelo ..\modelos\modelo_producao.tflite --teste ..\dados_teste --json ..\modelos\avaliacao.json
```

`limpar_dataset.py` só simula. Use `--executar` para mover as pastas `debug/` para fora do dataset.

## Parar

```bat
docker compose down
```

Os dados em `dados\` e `modelos\` ficam no seu PC.

## Erros comuns

| Sintoma | Causa provável |
|---|---|
| `api` reinicia e o log mostra "Modelo de producao nao encontrado" | Não há `modelo_producao.tflite` validado. Rode o treino |
| Treino: "Imagens refinadas insuficientes" | Menos de 10 fotos em alguma classe, ou fotos com menos de 1,5 s (espere e rode de novo) |
| Treino: "Modelo ja treinado com estas imagens" | Apague `candidato.tflite` e `metricas_treino.json` (passo acima) |
| Treino: "REPROVADO no portao" | Acurácia ou recall abaixo do mínimo. Confira as fotos e o `metricas_treino.json` |
| Pasta `*_refinada` vazia | Pasta `dados` não liberada no Docker Desktop (Settings > Resources > File sharing) |
| Porta 8000 ocupada | Troque para `"8001:8000"` no compose (e o `IA_BASE_URL` do backend) |
| `/health` com `model_loaded: false` | Modelo não carregou. Veja `docker compose logs api` |

## Limites

- A qualidade do modelo depende das suas fotos. O portão de qualidade protege a produção, mas não garante boa acurácia com fotos novas. Avalie com `dados_teste`.
- O download dos pesos da MobileNetV2 na primeira vez exige internet no Docker.
