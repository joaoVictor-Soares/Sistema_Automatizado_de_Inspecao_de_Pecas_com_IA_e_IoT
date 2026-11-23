# Backend Spring — inspeção de tampinhas

Backend que coordena a câmera USB, a ESP8266 (firmware `mesa-de-lorenzo`), a API de IA e o PostgreSQL.

```
Câmera USB ──► CameraService (ffmpeg) ──► ColetaAutomaticaService (demarcação)
                                                  │
                                                  ▼
                                   InspecaoOrchestratorService ──► PythonMlClient ──HTTP──► API de IA (/predict)
                                                  │
                          ┌───────────────────────┴──────────────────────┐
                          ▼                                              ▼
              PecasRecusadasService (PostgreSQL)              AtuadorServiceImpl ──► EspSerialGateway (NOK:<ms>)
```

- **Coleta automática:** monitora a demarcação (região da imagem com tampa verde) e envia cada tampa à IA. Só roda depois de `POST /api/inspecao/iniciar?confirmar=true` com a verificação aprovada.
- **Recusa:** se a IA reprovar, o backend envia `NOK:<ms>` à ESP, grava a peça e a foto no banco (`bytea`).
- **Fail-safe:** se a câmera ou a IA falharem com acionamento ligado, a peça é tratada como recusada (`inspecao.falha-reprova=true`).

## Requisitos

- JDK 21
- PostgreSQL (container: `docker run -d --name pg-inspecao -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=inspecao postgres:16`)
- FFmpeg (no PATH, ou `FFMPEG_PATH` apontando para o executável)
- API de IA no ar (`ia-docker`, porta 8000)

## Rodar

No Windows:

```bat
mvnw.cmd spring-boot:run
```

Na primeira subida com `ADMIN_SENHA` vazio, a senha gerada do usuário `admin@local` aparece no log.

## Configuração

Todas as propriedades estão em `src/main/resources/application.properties` e podem ser sobrescritas por variável de ambiente. Os valores abaixo são os padrões do código.

| Variável | Padrão | Para quê |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/inspecao`, `postgres`, `postgres` | Banco |
| `ESP_PORTA` | `COM7` | Porta serial da ESP (no Linux: `/dev/ttyUSB0`) |
| `ESP_BAUD_RATE` | `115200` | Velocidade do firmware |
| `ESP_TEMPO_ACIONAMENTO_MS` | `5000` | Tempo inicial do cilindro (ajustável em `PUT /api/atuador/configuracao`) |
| `ESP_SIMULADO` | `false` | `true` usa a ESP simulada em memória |
| `CAMERA_FORMATO` | `dshow` | `dshow` (Windows) ou `v4l2` (Linux) |
| `CAMERA_DSHOW` | `GENERAL WEBCAM` | Nome da câmera no DirectShow |
| `CAMERA_DEVICE` | `/dev/video0` | Dispositivo no v4l2 |
| `CAMERA_RESOLUCAO` | `640x480` | Resolução da captura. A webcam usada no teste só aceita `640x360` |
| `FFMPEG_PATH` | `ffmpeg` | Caminho do executável do ffmpeg |
| `CAMERA_IMAGEM_SIMULADA` | (vazio) | Arquivo ou pasta de imagens para testar sem câmera |
| `IA_BASE_URL` | `http://localhost:8000` | Endereço da API de IA |
| `INSPECAO_ATRASO_ATUACAO` | `4s` | Tempo entre a demarcação e o acionamento do cilindro |
| `INSPECAO_FALHA_REPROVA` | `true` | Peça não verificada é desviada como recusada |
| `DEMARCACAO_X`, `DEMARCACAO_Y`, `DEMARCACAO_LARGURA`, `DEMARCACAO_ALTURA` | `0.40`, `0.40`, `0.20`, `0.20` | Região da demarcação (fração da imagem) |
| `DEMARCACAO_LIMIAR_VERDE` | `0.08` | Fração de pixels verdes que indica tampa presente |
| `SEGURANCA_HABILITADA` | `true` | Exige token Bearer nas rotas |
| `SEGURANCA_SEGREDO` | (aleatório a cada subida) | Segredo dos tokens, com 32+ caracteres |
| `ADMIN_EMAIL`, `ADMIN_SENHA` | `admin@local`, (aleatória) | Administrador criado na primeira subida |
| `CORS_ORIGENS` | `*` | Origens permitidas |

## Endpoints principais

Todas as rotas exigem `Authorization: Bearer <token>`, exceto o login e o health.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/login` | Login (`email`, `senha`) e token |
| GET | `/api/inspecao/verificacao` | Testa câmera, ESP e IA sem iniciar nada |
| POST | `/api/inspecao/iniciar?confirmar=true` | Inicia a coleta automática (exige verificação OK) |
| POST | `/api/inspecao/parar` | Para a coleta |
| GET | `/api/inspecao/coleta` | `estado` e `pecasDetectadas` |
| POST | `/api/inspecao/disparar?atuar=` | Captura um frame e classifica |
| POST | `/api/inspecao/analisar?atuar=` | Classifica uma imagem enviada (multipart `file`) |
| GET | `/api/inspecao/status` · `/ultimo` | Estado e último resultado |
| GET | `/api/atuador/status` · `/configuracao` | Estado da ESP e tempo do cilindro |
| PUT | `/api/atuador/configuracao` | Altera `tempoAcionamentoMs` em tempo de execução |
| POST | `/api/atuador/acionar` | Aciona o cilindro pelo tempo configurado (**aciona de verdade**) |
| GET | `/api/pecas-recusadas` · `/{id}` · `/{id}/imagem` | Peças reprovadas e a foto gravada no banco |
| GET/POST | `/api/producao/esteira/{id}` · `/atual` · `/iniciar` | Contadores de produção |
| CRUD | `/api/esteiras`, `/api/usuarios` | Cadastros |
| GET | `/api/eventos` · `/stream` | Histórico de eventos e stream em tempo real |

Use `atuar=false` nas rotas de análise para testar sem acionar o cilindro e sem gravar no banco.

## Testes

```bat
mvnw.cmd test
```

Os testes usam H2 em memória (`application-test.properties`), sem ESP e sem câmera.

## Estrutura

| Pacote | Conteúdo |
|---|---|
| `service/` | Orquestração, câmera, coleta automática, ESP (serial e simulada), atuador, produção |
| `client/` | Cliente HTTP da API de IA (`PythonMlClient`) |
| `config/` | Propriedades (`@ConfigurationProperties`) e configuração do cliente HTTP |
| `controller/` | Rotas REST |
| `security/` | Login, tokens, filtro de autenticação e limite de tentativas |
| `model/`, `repository/`, `dto/` | Entidades JPA, repositórios e contratos JSON |

Para a migração para o Raspberry Pi, veja `GUIA-RASPBERRY-PI.md` na raiz do repositório.
