# Sistema Automatizado de Inspeção de Peças com IA e IoT

Projeto integrador do 4º termo do Tecnólogo em Análise e Desenvolvimento de Sistemas.

O sistema identifica tampinhas de garrafa defeituosas em uma esteira em tempo real. Uma câmera fotografa cada peça na região de inspeção, uma IA classifica a imagem como aprovada ou reprovada, e as peças reprovadas são desviadas por um cilindro pneumático. As fotos das peças recusadas ficam gravadas no banco para consulta.

---

## Como funciona

```
Câmera USB ──► Backend Spring ──► API de IA (Docker, TensorFlow Lite)
                 │   │                 │
                 │   │                 └── classifica: aprovada / reprovada
                 │   └──► PostgreSQL (peças recusadas e fotos)
                 │
                 └──► ESP8266 (serial) ──► driver ──► cilindro (desvia a peça)
```

1. O backend monitora a câmera. Quando a tampinha entra na **demarcação** (uma região da imagem com a cor verde da tampa), ele captura o quadro.
2. O quadro vai para a API de IA, que refina a imagem e classifica a peça.
3. **Aprovada:** nada acontece.
4. **Reprovada:** o backend envia `NOK:<ms>` à ESP8266. O cilindro é acionado pelo tempo indicado e a peça é gravada com a foto no banco.
5. O ciclo se repete até o operador parar a coleta.

Antes de iniciar, o backend verifica a câmera, a ESP e a IA. A coleta só começa com confirmação explícita do operador.

---

## Componentes

| Componente | Tecnologia | Função |
|---|---|---|
| Backend | Java 21, Spring Boot 4 | Orquestra câmera, ESP, IA e banco. Expõe a API REST com autenticação |
| Câmera | USB, FFmpeg (DirectShow no Windows, v4l2 no Linux) | Captura os quadros da inspeção |
| Firmware | ESP8266 NodeMCU (PlatformIO), pasta `mesa-de-lorenzo/` | Recebe `NOK:<ms>` pela serial a 115200 bps e aciona a saída do cilindro pelo tempo pedido |
| API de IA | Python, FastAPI, TensorFlow Lite, MobileNetV2 (Docker), pasta `ia-docker/` | Refina a imagem, classifica e expõe `/predict` |
| Treino | TensorFlow 2, pasta `ia-docker/treinamento/` | Treina um candidato e só o promove a produção se passar no portão de qualidade (acurácia ≥ 85%, recall mínimo ≥ 75%) |
| Banco | PostgreSQL | Peças recusadas, fotos (`bytea`) e contadores de produção |
| Captura Java (opcional) | JavaCV, pasta `captura-java/` | Coleta de fotos para treino. Não faz parte da produção |

### Sobre o CLP

O plano original previa um CLP Siemens S7-1500 comunicando-se por OPC-UA, com o Raspberry Pi como cliente e uma API na nuvem. A implementação atual usa a ESP8266 pela porta serial no lugar do CLP. A parte de nuvem e o aplicativo móvel ainda não foram implementados.

---

## Segurança

- Todas as rotas da API exigem token Bearer, exceto o login e o health check.
- Há dois perfis: **OPERADOR** e **ADMIN**. Rotas de cadastro de usuários e de simulação são só para ADMIN.
- O login tem limite de tentativas por janela de tempo.
- Os tokens são assinados com um segredo configurável (`SEGURANCA_SEGREDO`). Sem ele, o segredo muda a cada subida e os tokens antigos deixam de valer.
- A senha do administrador vem de `ADMIN_SENHA`. Se não for definida, uma senha aleatória aparece no log da primeira subida.

---

## Estrutura do repositório

| Pasta | Conteúdo |
|---|---|
| `backend-spring-completo/backend-spring/demo/` | Backend Spring (Maven wrapper `mvnw`) |
| `ia-docker/` | API de IA, treino, avaliação e `docker-compose.yml` |
| `mesa-de-lorenzo/mesa-de-lorenzo/` | Firmware da ESP8266 (PlatformIO) |
| `captura-java/` | Captura Java opcional para coleta de fotos |
| `backend-esp/` | Notas de alteração do backend para o firmware atual |
| `docs/` | Documentação original do projeto (diagramas, lista de materiais, modelo de dados) |
| `COMO-RODAR-O-SISTEMA.md` | Passo a passo para subir o sistema no Windows |
| `GUIA-RASPBERRY-PI.md` | Plano de migração do backend, câmera e ESP para o Raspberry Pi 4 |
| `LEIA-ME-TESTE-BACKEND.md` | Roteiro curto de testes do backend |

---

## Como rodar

O passo a passo completo está em [`COMO-RODAR-O-SISTEMA.md`](../COMO-RODAR-O-SISTEMA.md). Resumo:

1. Gravar o firmware na ESP8266 (`mesa-de-lorenzo/mesa-de-lorenzo`).
2. Subir o PostgreSQL em container.
3. Treinar o modelo e subir a API de IA com `docker compose` em `ia-docker/`.
4. Subir o backend com `mvnw.cmd spring-boot:run` em `backend-spring-completo/backend-spring/demo/`.
5. Fazer login, conferir `GET /api/inspecao/verificacao` e iniciar a coleta.

---

## Stack

- **Backend:** Java 21, Spring Boot 4 (Web, Security, Data JPA, Actuator), jSerialComm
- **IA:** Python 3.11, FastAPI, OpenCV e TFLite (`ai-edge-litert`) na API; TensorFlow 2 só no treino
- **Firmware:** C++ (Arduino), PlatformIO, ESP8266 NodeMCU
- **Infraestrutura:** Docker, PostgreSQL 16

---

## Status

- **Validado na bancada (Windows):** verificação de câmera, ESP e IA; coleta automática; classificação de peça reprovada; envio de `NOK:4000` com acionamento e desacionamento do cilindro.
- **Pendente:** calibração da demarcação, do atraso até o cilindro e do tempo do cilindro; acurácia com fotos novas; migração para o Raspberry Pi (ver `GUIA-RASPBERRY-PI.md`).
