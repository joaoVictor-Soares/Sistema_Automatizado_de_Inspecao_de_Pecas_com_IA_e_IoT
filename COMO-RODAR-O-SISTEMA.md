# Como subir e rodar o sistema de inspeção de tampinhas

Este guia substitui o `ia-docker/LEIA-ME-IA.md` (que descreve o fluxo antigo, com modelo embutido e treino automático). Siga na ordem. Os comandos são para **Windows** (cmd e PowerShell).

> **Aviso:** nada deste guia foi executado de ponta a ponta em hardware real. Ele descreve o que está no código. Cada etapa tem um teste de verificação; só avance quando o teste passar.

---

## 0. Visão geral

```
Câmera USB ──► Backend Spring (Windows) ──► API de IA (Docker, porta 8000)
                 │   │                          │
                 │   └──► PostgreSQL (banco)     └── classifica: aprovadas / reprovadas
                 │
                 └──► ESP8266 (COM7) ──► 74HC595/ULN2803 ──► cilindro (recusa a peça)
```

Fluxo com a coleta automática ligada:

1. O backend monitora a câmera. Quando a tampinha verde entra na **demarcação** (região da imagem), ele captura o frame.
2. O frame vai para a API de IA, que refina a imagem e classifica.
3. **Aprovada:** nada acontece.
4. **Reprovada:** o backend envia `NOK:<ms>` à ESP pela COM7; o cilindro aciona pelo tempo configurado e a foto da peça vai para o banco.
5. O ciclo se repete até você chamar `parar`.

### Pré-requisitos

| Item | Para quê |
|---|---|
| Windows 10/11 | Sistema de testes |
| Docker Desktop (com Compose) | API de IA e, opcionalmente, o PostgreSQL |
| JDK 21 | Backend Spring (`mvnw.cmd` baixa o Maven) |
| FFmpeg no PATH | Captura da câmera (`ffmpeg -version` deve responder) |
| Python 3.11 (`py -3.11`) | Só para os scripts de treino/avaliação fora do Docker (opcional) |
| VS Code + extensão PlatformIO | Gravar o firmware na ESP8266 |
| Câmera USB | Única câmera do PC |
| ESP8266 NodeMCU | Placa com o firmware `mesa-de-lorenzo`, na COM7 |

---

## 1. Preparar o repositório

Clone o repositório e entre na pasta:

```bat
git clone https://github.com/joaoVictor-Soares/Sistema_Automatizado_de_Inspecao_de_Pecas_com_IA_e_IoT
cd Sistema_Automatizado_de_Inspecao_de_Pecas_com_IA_e_IoT
git checkout feat/ajustes-handoff
```

Estrutura que você vai usar:

| Pasta | Conteúdo |
|---|---|
| `ia-docker/` | API de IA, treino e `docker-compose.yml` |
| `ia-docker/dados/aprovada_refinada/` e `reprovada_refinada/` | Fotos já refinadas, usadas no treino |
| `ia-docker/modelos/` | Onde ficam `candidato.tflite` e `modelo_producao.tflite` (criada na primeira execução) |
| `backend-spring-completo/backend-spring/demo/` | Backend Spring (`mvnw.cmd`, `pom.xml`) |
| `mesa-de-lorenzo/mesa-de-lorenzo/` | Firmware da ESP8266 (PlatformIO) |
| `backend-spring-completo/backend-spring/demo/src/main/java/com/pi/demo/captura-java/` | Captura Java (só para coletar fotos, opcional) |

---

## 2. Descobrir o nome da câmera

Com a câmera conectada, rode:

```bat
ffmpeg -list_devices true -f dshow -i dummy
```

Procure a linha com `(video)` e copie o nome exato entre aspas. No seu caso é `GENERAL WEBCAM`. Esse nome vai na variável `CAMERA_DSHOW` (passo 6).

**Teste:** o comando lista pelo menos uma câmera de vídeo. Se nenhuma aparecer, feche outros programas que usem a câmera (Teams, app da câmera do Windows) e tente de novo.

---

## 3. Gravar o firmware na ESP8266 (COM7)

1. No VS Code, com a extensão PlatformIO, abra a pasta `mesa-de-lorenzo/mesa-de-lorenzo`.
2. Confirme que o ambiente é `nodemcuv2` (arquivo `platformio.ini`).
3. Conecte a ESP na USB. Confirme que ela aparece como **COM7** no Gerenciador de Dispositivos. Se for outra porta, use essa e lembre de trocar `ESP_PORTA` no passo 6.
4. Clique em **Upload** (ou `pio run -t upload`).
5. Abra o monitor serial a 115200 bps. Deve aparecer `Gateway IoT ESP8266 - saidas inicializadas desabilitadas`.
6. Digite `NOK:1000` e Enter. Deve aparecer `NOK recebido: saida 0 acionada por 1000 ms` e o cilindro deve acionar por 1 segundo. Depois aparece `Saida 0 desacionada`.

> **Atenção:** só um programa pode usar a COM7 por vez. Feche o monitor serial do VS Code/PlatformIO antes de rodar o backend. Se o backend mostrar que a porta está ocupada, é isso.

**Teste:** o cilindro acionou pelo tempo do comando. Se não acionou, confira a fiação (passo de hardware) antes de seguir.

---

## 4. Banco de dados PostgreSQL

O backend usa PostgreSQL em `localhost:5432`, banco `inspecao`, usuário `postgres`, senha `postgres` (padrões do `application.properties`). O jeito mais simples é um container:

```bat
docker run -d --name pg-inspecao -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=inspecao -p 5432:5432 postgres:16
```

O backend cria as tabelas sozinho na primeira subida (`DB_DDL_AUTO=update`). A tabela de peças recusadas ganha a coluna `imagem` (tipo `bytea`) nesse processo.

**Teste:**

```bat
docker exec -it pg-inspecao psql -U postgres -d inspecao -c "select 1;"
```

Deve retornar uma linha com `1`.

---

## 5. Subir a API de IA (Docker)

### 5.1 Treinar o modelo de produção (uma vez, e sempre que trocar as fotos)

A API **não sobe sem um modelo de produção validado** (decisão E2). O treino gera um candidato e só o promove se passar no portão de qualidade (acurácia ≥ 85% e recall mínimo ≥ 75%, configuráveis).

Confirme que as pastas têm imagens (mínimo 10 em cada uma):

```bat
cd ia-docker
dir dados\aprovada_refinada | find /c "png"
dir dados\reprovada_refinada | find /c "png"
```

Rode o treino sob demanda:

```bat
docker compose run --rm -e TREINAR_AO_SUBIR=true treino
```

O que esperar no log:

- `Imagens refinadas por classe: {...}`
- `Treinando candidato com N imagens`
- `Candidato aprovado (acuracia X%, recall minimo Y%). Promovido para producao: /modelos/modelo_producao.tflite`
  **ou**
- `Candidato REPROVADO no portao (...). Producao nao foi alterada`

Se for reprovado, o modelo de produção **não muda**. Veja os números em `ia-docker\modelos\metricas_treino.json` e volte a conferir as fotos (passo 5.3).

> O treino da MobileNetV2 baixa os pesos do ImageNet na primeira vez. É preciso internet no Docker nesse momento.

### 5.2 Subir a API

```bat
docker compose up -d --build api
```

O serviço `api` só fica saudável com `modelo_producao.tflite` em `ia-docker\modelos\`. Sem ele, o container reinicia e o log mostra: `Modelo de producao nao encontrado em /modelos/modelo_producao.tflite`.

**Teste:**

```bat
curl.exe http://localhost:8000/health
```

Resposta esperada (campos principais): `"status":"ok"`, `"model_loaded":true`, `"modelo":"/modelos/modelo_producao.tflite"`, `"refinamento":true`.

Para testar uma foto:

```bat
curl.exe -F "file=@C:\caminho\foto.png" http://localhost:8000/predict
```

Deve voltar `status` (`aprovadas` ou `reprovadas`) e `confianca`.

### 5.3 Conferir a qualidade antes de confiar (recomendado)

Use um conjunto de fotos **que não foram usadas no treino**:

```bat
cd ia-docker\treinamento
py -3.11 limpar_dataset.py --dados ..\dados
py -3.11 avaliar_modelo.py --modelo ..\modelos\modelo_producao.tflite --teste ..\dados_teste --json ..\modelos\avaliacao.json
```

`limpar_dataset.py` só simula. Rode com `--executar` para mover as pastas `debug/` para fora do dataset.

---

## 6. Subir o backend Spring

### 6.1 Variáveis de ambiente

Defina só o que for diferente do padrão. No **cmd**, use `set`; no **PowerShell**, use `$env:`.

| Variável | Valor sugerido | Para quê |
|---|---|---|
| `ESP_PORTA` | `COM7` | Porta da ESP (padrão já é COM7) |
| `CAMERA_DSHOW` | `GENERAL WEBCAM` | Nome da câmera (passo 2) |
| `CAMERA_FORMATO` | `dshow` | Padrão já é dshow no Windows |
| `IA_BASE_URL` | `http://localhost:8000` | API de IA (padrão já é esse) |
| `INSPECAO_ATRASO_ATUACAO` | `4s` | Tempo entre a demarcação e o cilindro (padrão 4s; calibrar) |
| `ESP_TEMPO_ACIONAMENTO_MS` | `5000` | Tempo que o cilindro fica acionado (padrão 5000) |
| `DEMARCACAO_X`, `_Y`, `_LARGURA`, `_ALTURA` | `0.40`, `0.40`, `0.20`, `0.20` | Região da demarcação (fração da imagem) |
| `DEMARCACAO_LIMIAR_VERDE` | `0.08` | Fração de pixels verdes que indica tampa presente |
| `ADMIN_SENHA` | (sua senha) | Senha do administrador na primeira subida. Se vazia, a senha aleatória aparece no log |
| `SEGURANCA_SEGREDO` | string com 32+ caracteres | Segredo dos tokens. Se vazio, muda a cada subida e os tokens antigos param de valer |

Exemplo (cmd):

```bat
set ESP_PORTA=COM7
set CAMERA_DSHOW=GENERAL WEBCAM
set ADMIN_SENHA=troque-esta-senha
set SEGURANCA_SEGREDO=troque-por-uma-chave-com-pelo-menos-32-caracteres
```

### 6.2 Rodar

```bat
cd backend-spring-completo\backend-spring\demo
mvnw.cmd spring-boot:run
```

Na primeira vez o Maven baixa as dependências (demora). O backend sobe em `http://localhost:8080`.

Se estiver na primeira subida, procure no log a linha com o usuário administrador (`admin@local`) e a senha gerada, caso não tenha definido `ADMIN_SENHA`.

**Teste:**

```bat
curl.exe http://localhost:8080/actuator/health
```

Deve responder `{"status":"UP"}`.

---

## 7. Login e autenticação

Todos os endpoints de inspeção exigem token. Faça login:

```bat
curl.exe -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"email\":\"admin@local\",\"senha\":\"SUA_SENHA\"}"
```

A resposta traz o token. Use-o nas chamadas seguintes com o cabeçalho `Authorization: Bearer <token>`.

> Dica: grave o comando longo em um arquivo `login.json` e use `-d "@login.json"`, para não estourar o limite da linha de comando do PowerShell.

---

## 8. Verificação de inicialização (antes de ligar a esteira)

Com o token em `TOKEN`:

```bat
curl.exe http://localhost:8080/api/inspecao/verificacao -H "Authorization: Bearer %TOKEN%"
```

A resposta tem três itens (`camera`, `esp`, `ia`), cada um com `ok` e `mensagem`, e `tudoOk`.

- Se `tudoOk` for `true`, pode iniciar.
- Se algum item for `false`, a `mensagem` diz o que falhou (câmera em uso, COM7 ocupada, API de IA fora do ar, modelo não carregado).

**Não inicia nada.** É só diagnóstico.

---

## 9. Iniciar, acompanhar e parar a coleta automática

### 9.1 Iniciar (com confirmação)

```bat
curl.exe -X POST "http://localhost:8080/api/inspecao/iniciar?confirmar=true" -H "Authorization: Bearer %TOKEN%"
```

- Sem `confirmar=true`, recusa.
- Se a verificação falhar, recusa e informa o motivo.
- Se passar, a coleta começa e o estado vira `MONITORANDO`.

### 9.2 Acompanhar

```bat
curl.exe http://localhost:8080/api/inspecao/coleta -H "Authorization: Bearer %TOKEN%"
```

Mostra `estado` e `pecasDetectadas` (quantas tampas foram enviadas à IA).

Logs do backend: quando a tampa entra na demarcação aparece `Tampa na demarcacao (verde X%): enviando para a IA.`. Use esse `X%` para calibrar (passo 10).

### 9.3 Parar

```bat
curl.exe -X POST http://localhost:8080/api/inspecao/parar -H "Authorization: Bearer %TOKEN%"
```

---

## 10. Calibração (faça na mesa, nesta ordem)

### 10.1 Demarcação

1. Inicie a coleta sem peças e confirme no log que `verde` fica próximo de 0%.
2. Passe uma tampa verde pela região e veja o `verde X%` quando ela entrar.
3. Se a tampa **não** dispara, baixe `DEMARCACAO_LIMIAR_VERDE` (ex.: `0.05`). Se dispara sem tampa (fundo verde), suba.
4. Se a região não pega a tampa, ajuste `DEMARCACAO_X`/`DEMARCACAO_Y`/`DEMARCACAO_LARGURA`/`DEMARCACAO_ALTURA`.
5. Reinicie o backend após mudar essas variáveis.

### 10.2 Atraso até o cilindro

`INSPECAO_ATRASO_ATUACAO` é o tempo entre a demarcação e o acionamento. Meça o tempo da esteira entre a câmera e o cilindro e ajuste. Se o log avisar `Latencia da IA (...) maior que inspecao.atraso-atuacao`, a IA está mais lenta que o atraso e a peça pode passar. Aumente o atraso ou acelere a IA.

### 10.3 Tempo do cilindro

Defina por `ESP_TEMPO_ACIONAMENTO_MS` (passo 6.1) ou em tempo de execução:

```bat
curl.exe -X PUT http://localhost:8080/api/atuador/configuracao -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\"tempoAcionamentoMs\":4000}"
```

O backend envia esse tempo ao firmware a cada `NOK`. O padrão do firmware (5000 ms) só vale se o comando vier sem tempo.

### 10.4 Teste manual do cilindro (sem esteira)

```bat
curl.exe -X POST http://localhost:8080/api/atuador/acionar -H "Authorization: Bearer %TOKEN%"
```

Aciona o cilindro pelo tempo configurado. Use para checar fiação e curso.

### 10.5 Teste de uma foto sem acionar o cilindro

```bat
curl.exe -X POST "http://localhost:8080/api/inspecao/analisar?atuar=false" -H "Authorization: Bearer %TOKEN%" -F "file=@C:\caminho\foto.png"
```

`atuar=false` só classifica. Use para validar a IA com peças reais antes de ligar o acionamento.

---

## 11. Fotos da peça reprovada

Quando a peça é reprovada e o acionamento está ativo, o backend grava o registro em `pecas_recusadas` com a imagem no banco. Para consultar:

- `GET /api/pecas-recusadas` lista os registros.
- `GET /api/pecas-recusadas/{id}/imagem` devolve a foto.

Registros antigos (gravados em disco) continuam funcionando pelo mesmo endpoint.

---

## 12. Captura Java (opcional: só para coletar fotos)

A captura Java serve para **juntar fotos** para treino, não para produção. Em produção, quem aciona o cilindro é o backend.

- Está em `backend-spring-completo/.../captura-java/CapturaTampinha.java`, com `pom.xml` na mesma pasta.
- O acionamento direto da ESP fica **desligado** por padrão. Para bancada, use `--acionar-direto`. Nunca use junto com `--backend`.
- Coleta de fotos sem ESP: `--sem-esp`.

> Esta parte **não foi testada** neste ambiente. Confirme a compilação pelo `pom.xml` antes de usar.

---

## 13. Ordem completa de subida (checklist)

1. [ ] Câmera conectada, nome conferido (`ffmpeg -list_devices`).
2. [ ] ESP gravada e respondendo na COM7 (`NOK:1000` aciona o cilindro).
3. [ ] Monitor serial fechado (a COM7 fica livre para o backend).
4. [ ] PostgreSQL rodando (`select 1` ok).
5. [ ] `modelo_producao.tflite` existe em `ia-docker\modelos\`.
6. [ ] API de IA no ar (`/health` com `model_loaded: true`).
7. [ ] Variáveis de ambiente do backend definidas.
8. [ ] Backend no ar (`/actuator/health` UP).
9. [ ] Login feito, token guardado.
10. [ ] `GET /api/inspecao/verificacao` com `tudoOk: true`.
11. [ ] `POST /api/inspecao/iniciar?confirmar=true`.
12. [ ] Demarcação e atraso calibrados.

---

## 14. Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| API de IA reinicia e o log diz "Modelo de producao nao encontrado" | Ainda não há modelo validado | Passo 5.1 |
| Treino diz "REPROVADO no portao" | Modelo não atingiu acurácia/recall mínimos | Conferir fotos (`limpar_dataset.py`), ver `metricas_treino.json`, depois treinar de novo |
| `/health` da IA com `model_loaded: false` | Modelo não carregou | Ver o log do container (`docker compose logs api`) |
| Verificação: "Camera indisponivel" | Nome errado em `CAMERA_DSHOW` ou câmera em uso | Passo 2; fechar outros apps de câmera |
| Verificação: "ESP nao conectado" / porta ocupada | COM7 em uso por outro programa | Fechar monitor serial e a captura Java |
| `400 Invalid HTTP request received` na chamada à IA | Cliente HTTP da versão antiga | Já corrigido (HTTP/1.1). Se voltar, reconstruir o backend (`mvnw.cmd clean package`) |
| `[dshow] Could not set video options` ou avisos de `swscaler` | Avisos da câmera, geralmente inofensivos | Só preocupa se a captura falhar de fato |
| Peça passa sem ser recusada | Atraso ou tempo do cilindro mal calibrados | Passo 10.2 e 10.3 |
| Muitas recusas com a IA fora do ar | Política de falha em modo A (fail-safe) | Esperado: `inspecao.falha-reprova=true`. Ver E9 |
| Tokens param de funcionar ao reiniciar o backend | `SEGURANCA_SEGREDO` não definido | Definir o segredo com 32+ caracteres |

---

## 15. O que ainda não foi validado

- Compilação do backend e da captura Java no Windows (`mvnw.cmd` e Maven não rodaram neste ambiente).
- Gravação do firmware e o acionamento real do cilindro com a fiação final.
- Treino da MobileNetV2 (depende da internet para baixar os pesos).
- Acurácia do modelo com fotos novas (passo 5.3).
- Latência da câmera: cada frame é uma chamada ao FFmpeg, o que pode perder tampas que passam rápido. Se isso acontecer na mesa, o próximo passo é manter um stream contínuo da câmera.
