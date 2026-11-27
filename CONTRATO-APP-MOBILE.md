# Contrato do app mobile com o backend

Este documento define o que o app mobile (React Native) deve ter e como ele conversa com o backend Spring. Cada tela e cada botão indica o endpoint chamado, o que é enviado e o que é esperado de volta.

Todos os exemplos usam a base `http://IP_DO_BACKEND:8080`. O IP é o do computador que roda o backend na rede local.

---

## 1. Convenções gerais

### 1.1 Autenticação

- Login em `POST /api/auth/login`. A resposta traz `token`. Guarde o token no armazenamento seguro do dispositivo.
- Toda chamada, exceto o login e o health check, envia o cabeçalho:
  `Authorization: Bearer <token>`
- O token vale **8 horas**. Quando expirar, ou quando o backend reiniciar sem `SEGURANCA_SEGREDO` fixo, a API responde **401**. Nesse caso, o app apaga o token e volta para a tela de login.

### 1.2 Perfis

| Perfil | O que pode fazer |
|---|---|
| `OPERADOR` | Ver e operar a inspeção, consultar peças recusadas, eventos, produção e atuador (leitura) |
| `ADMIN` | Tudo do operador, mais: cadastrar e remover usuários, criar e remover esteiras, iniciar produção, simulação |

Os perfis são aplicados pelo backend. O app também deve esconder os botões de ADMIN quando o perfil do usuário logado for `OPERADOR` (ver seção 3.1).

### 1.3 Formato dos dados

- JSON com nomes de campos em `camelCase`.
- Datas e horas em **ISO 8601 sem fuso** (`2026-11-26T14:05:12.345`), exceto `expiraEm` do login, que vem com fuso (`2026-11-26T22:05:12Z`).
- Valores numéricos são números JSON, não texto.

### 1.4 Erros

Todas as respostas de erro usam o formato **ProblemDetail**:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Mensagem legível para mostrar ao usuário",
  "instance": "/api/inspecao/iniciar"
}
```

O app deve mostrar `detail` ao usuário quando existir.

| Status | Quando acontece | O que o app mostra |
|---|---|---|
| 400 | Parâmetro inválido, operação não permitida no estado atual | `detail` |
| 401 | Token ausente, inválido ou expirado | "Sessão expirada, faça login de novo" e volta ao login |
| 403 | Perfil sem permissão | "Você não tem permissão para esta ação" |
| 404 | Recurso não encontrado | `detail` |
| 409 | Conflito de dados (registro em uso ou duplicado) | `detail` |
| 413 | Arquivo maior que 10 MB | "Imagem muito grande" |
| 429 | Muitas tentativas de login | `detail` (aguardar alguns minutos) |
| 502 | A IA não respondeu | "Falha na IA de inspeção" |
| 503 | ESP ou câmera indisponível | `detail` (ex.: "ESP inacessível") |
| 500 | Erro interno | "Erro no servidor" |

---

## 2. Telas e botões

### 2.1 Tela de login

**Campos:** e-mail, senha.

**Botão "Entrar":**

- Chama `POST /api/auth/login`
- Envia:
  ```json
  { "email": "admin@local", "senha": "senha-do-usuario" }
  ```
- Espera `200` com:
  ```json
  {
    "token": "MnxBRE1JTn...",
    "tipo": "Bearer",
    "expiraEm": "2026-11-26T22:05:12Z",
    "usuario": { "id": 1, "nome": "Administrador", "email": "admin@local", "perfil": "ADMIN" }
  }
  ```
- Ao receber, guardar `token`, `usuario.perfil` e `expiraEm`.
- Erros esperados: **401** (`E-mail ou senha invalidos.`), **429** (`Muitas tentativas de login...`).

**Botão "Sair":** não há endpoint de logout. O app apaga o token guardado. O token continua válido até expirar, então o app não deve mais enviá-lo.

**Alterar a própria senha** (opcional, em "Minha conta"):
- `POST /api/auth/senha`
- Envia `{ "senhaAtual": "...", "novaSenha": "..." }`
- Espera `200` sem corpo. Erro **400** se a senha atual estiver errada.

**Ao abrir o app com token guardado:**
- Chamar `GET /api/auth/eu`. Espera `200` com `{ id, nome, email, perfil }`.
- Se der **401**, voltar ao login.

---

### 2.2 Tela inicial (painel)

Mostra o estado da máquina em tempo real. Atualize a cada 5 segundos, ou use o stream de eventos (seção 2.8).

| Dado na tela | Endpoint | Campo usado |
|---|---|---|
| Estado da coleta automática | `GET /api/inspecao/coleta` | `estado` (`PARADO` ou `MONITORANDO`), `pecasDetectadas` |
| Última peça classificada | `GET /api/inspecao/ultimo` | ver seção 2.5 |
| IA disponível, fila, total | `GET /api/inspecao/status` | `iaDisponivel`, `pendentes`, `totalInspecionadas` |
| Cilindro e ESP | `GET /api/atuador/status` | `conectado`, `simulado`, `porta`, `atuadorAcionado`, `tempoAcionamentoMs` |
| Produção atual (por esteira) | `GET /api/producao/esteira/{esteiraId}/atual` | `totalProcessadas`, `totalAprovadas`, `totalRecusadas`, `taxaRecusa` |

**Detalhes:**

- `GET /api/inspecao/ultimo` retorna **204 (sem corpo)** se ainda não houve nenhuma inspeção. O app mostra "Aguardando primeira peça".
- `GET /api/producao/esteira/{esteiraId}/atual` pode retornar **404** se a produção não foi iniciada para a esteira. O app mostra "Produção não iniciada" e, se for ADMIN, o botão da seção 2.6.

---

### 2.3 Tela de inspeção (iniciar e parar)

Esta é a tela principal de operação.

**Botão "Verificar equipamentos":**
- Chama `GET /api/inspecao/verificacao`
- Não inicia nada. Só testa câmera, ESP e IA.
- Resposta:
  ```json
  {
    "camera": { "ok": true, "mensagem": "Camera OK ..." },
    "esp": { "ok": true, "mensagem": "ESP conectado (COM7)." },
    "ia": { "ok": true, "mensagem": "API de IA respondendo com modelo carregado." },
    "tudoOk": true
  }
  ```
- Mostrar cada item com ✓ ou ✗ usando `ok` e `mensagem`.

**Botão "Iniciar inspeção":**
- Só habilitado quando a última verificação retornou `tudoOk: true`.
- Antes de enviar, mostrar um diálogo de confirmação ("Iniciar a coleta automática?").
- Chama `POST /api/inspecao/iniciar?confirmar=true`
- Não envia corpo.
- Resposta de sucesso: `200` com a verificação (mesmo formato acima).
- Erros:
  - **400** `Inicializacao recusada: ...` quando algum equipamento falha. Mostrar o `detail`.
  - **400** `A coleta ja esta em execucao.` Tratar como sucesso e atualizar o estado.
  - **400** `Confirme a inicializacao (confirmar=true).` Nunca deve acontecer, porque o app sempre envia `confirmar=true`.
  - **503** ESP ou câmera indisponível.

**Botão "Parar inspeção":**
- Chama `POST /api/inspecao/parar`
- Não envia corpo.
- Resposta: `{ "estado": "PARADO" }`.

**Atualização do estado:**
- Chama `GET /api/inspecao/coleta` a cada 2 segundos enquanto a tela estiver aberta.
- Resposta: `{ "estado": "MONITORANDO", "pecasDetectadas": 12 }`.
- `pecasDetectadas` é o número de tampinhas que a coleta enviou para a IA desde o último "Iniciar inspeção".

---

### 2.4 Tela de teste manual (disparo)

Usada para testar a câmera e a IA sem depender da passagem da peça. Pode ficar só para ADMIN.

**Botão "Classificar agora (sem acionar)":**
- Chama `POST /api/inspecao/disparar?atuar=false`
- Não envia corpo.
- Captura um quadro da câmera e classifica. Não grava nada e não move o cilindro.

**Botão "Classificar agora (com acionamento)":**
- Chama `POST /api/inspecao/disparar?atuar=true`
- Se a peça for reprovada, o backend grava no banco e aciona o cilindro. Mostrar um aviso antes de enviar.

**Botão "Classificar foto da galeria":**
- Chama `POST /api/inspecao/analisar?atuar=false` com `multipart/form-data`
- Campo do formulário: `file` (a imagem, JPEG ou PNG, até 10 MB)
- Sem `atuar=true`, só classifica.

**Resposta das três rotas** (`ResultadoInspecaoDto`):

```json
{
  "origem": "CAMERA:MANUAL",
  "dataHora": "2026-11-26T14:05:12.345",
  "status": "reprovadas",
  "confianca": 91.4,
  "probabilidades": { "aprovadas": 8.6, "reprovadas": 91.4 },
  "acaoIa": "...",
  "atuacaoAgendada": false,
  "pecaRecusadaId": null,
  "erro": null
}
```

- `status`: `aprovadas`, `reprovadas` ou `erro`.
- `confianca` e `probabilidades` estão em percentual (0 a 100).
- `atuacaoAgendada`: `true` quando o cilindro foi acionado (ou vai ser).
- `pecaRecusadaId`: id da peça gravada, quando foi reprovada e `atuar=true`. Use para mostrar a foto (seção 2.5).
- Se `status` for `erro`, mostrar o campo `erro`.

**Parâmetros opcionais:**
- `operadorId` (inteiro): operador que está fazendo a inspeção. Se não for enviado, o backend usa o usuário do token.

---

### 2.5 Tela de peças recusadas

**Lista:**
- Chama `GET /api/pecas-recusadas`
- Parâmetro opcional: `esteiraId` (inteiro), para filtrar por esteira.
- Resposta: lista de:
  ```json
  {
    "id": 42,
    "esteiraId": 1,
    "operadorId": 1,
    "dataHora": "2026-11-26T14:05:12.345",
    "imagemUrl": "/api/pecas-recusadas/42/imagem",
    "erroIdentificado": "Falha na verificacao",
    "nivelAcuracia": 91.40
  }
  ```
- Ordenar pela `dataHora` mais recente, no app.

**Detalhe de uma peça** (ao tocar na linha):
- Chama `GET /api/pecas-recusadas/{id}`
- Resposta: o mesmo objeto acima.

**Foto da peça:**
- Chama `GET /api/pecas-recusadas/{id}/imagem`
- Resposta: a imagem (`image/jpeg`).
- **Importante:** exige o cabeçalho `Authorization`. Um `<Image source={{ uri }}>` sem cabeçalho recebe 401. Passe os headers: `source={{ uri, headers: { Authorization: 'Bearer ' + token } }}`.
- Monte a URL como `BASE + imagemUrl`.

**Última peça recusada (tela de inspeção):**
- Use `pecaRecusadaId` de `GET /api/inspecao/ultimo` quando `status` for `reprovadas`.

---

### 2.6 Tela de produção

**Histórico:**
- Chama `GET /api/producao/esteira/{esteiraId}`
- Resposta: lista de:
  ```json
  {
    "id": 1,
    "esteiraId": 1,
    "dataHoraInicio": "2026-11-26T08:00:00",
    "totalProcessadas": 120,
    "totalAprovadas": 105,
    "totalRecusadas": 15,
    "taxaRecusa": 12.5
  }
  ```
- `taxaRecusa` é percentual (0 a 100), com 2 casas.

**Produção atual:**
- Chama `GET /api/producao/esteira/{esteiraId}/atual`
- Mesmo formato de um item do histórico.

**Botão "Iniciar produção"** (somente ADMIN):
- Chama `POST /api/producao/esteira/{esteiraId}/iniciar`
- Não envia corpo.
- Resposta: o novo registro de produção.
- Erro **403** para OPERADOR. Esconder o botão para esse perfil.

---

### 2.7 Tela do atuador (cilindro)

**Status:**
- Chama `GET /api/atuador/status`
- Resposta:
  ```json
  {
    "conectado": true,
    "simulado": false,
    "porta": "COM7",
    "sensorAtivo": false,
    "atuadorAcionado": false,
    "tempoAcionamentoMs": 4000,
    "lidoEm": "2026-11-26T14:05:12.345"
  }
  ```
- Mostrar `conectado` (ESP online) e `atuadorAcionado` (cilindro em movimento).

**Tempo do cilindro:**
- Chama `GET /api/atuador/configuracao` para ler: `{ "tempoAcionamentoMs": 4000 }`.
- Chama `PUT /api/atuador/configuracao` para alterar. Envia `{ "tempoAcionamentoMs": 4000 }`. Valor entre **50 e 10.000 ms**; fora disso, erro **400**.
- O valor não é guardado: ao reiniciar o backend, volta ao padrão.

**Botão "Testar cilindro"** (somente ADMIN, com confirmação):
- Chama `POST /api/atuador/acionar`
- Não envia corpo.
- Aciona o cilindro de verdade pelo tempo configurado. A resposta só volta quando o ciclo termina.
- Resposta: `AtuadorStatusDto` (mesmo formato do status).

---

### 2.8 Tela de eventos (histórico e tempo real)

**Histórico recente:**
- Chama `GET /api/eventos?depois=0&limite=50`
- Parâmetros:
  - `depois` (long, padrão 0): devolve só eventos com `id` maior que esse valor. Use o maior `id` recebido para buscar só os novos.
  - `limite` (int, padrão 50, máximo 200).
- Resposta: lista de:
  ```json
  {
    "id": 105,
    "dataHora": "2026-11-26T14:05:12.345",
    "tipo": "PECA_RECUSADA",
    "severidade": "AVISO",
    "mensagem": "Peca reprovada (91.4%).",
    "dados": { "...": "..." }
  }
  ```

**Tempo real (stream):**
- Chama `GET /api/eventos/stream` (Server-Sent Events, `text/event-stream`).
- Cada evento chega com `event: <tipo>`, `id: <id>` e `data: <json do evento>`.
- **Importante:** o stream exige o cabeçalho `Authorization`. O `EventSource` nativo do navegador não envia cabeçalhos. No React Native, use uma biblioteca de SSE que aceite headers. Se isso não for viável, use o polling de `GET /api/eventos?depois=` a cada 2 segundos.
- O servidor envia um comentário `ping` a cada 25 segundos. Ignore.

**Tipos de evento e o que mostrar:**

| `tipo` | Severidade | Quando acontece | Sugestão de exibição |
|---|---|---|---|
| `PECA_APROVADA` | INFO | Peça aprovada | Lista, sem alerta |
| `PECA_RECUSADA` | AVISO | Peça reprovada | Alerta amarelo, com foto (`pecaRecusadaId` em `dados`) |
| `INSPECAO_ERRO` | ERRO | Falha na inspeção | Alerta vermelho |
| `VERIFICACAO` | INFO ou AVISO | Resultado de `/verificacao` | Lista |
| `COLETA_INICIADA` | INFO | Coleta começou | Lista |
| `COLETA_PARADA` | INFO | Coleta parou | Lista |
| `COLETA_ERRO` | ERRO | Erro na coleta | Alerta vermelho |
| `ESP_OFFLINE` | ERRO | ESP inacessível | Alerta vermelho fixo na tela inicial |
| `ESP_ONLINE` | INFO | ESP voltou | Remove o alerta de `ESP_OFFLINE` |
| `ATUADOR_ACIONADO` | INFO | Cilindro acionou | Lista |
| `ATUADOR_DESLIGADO` | INFO | Cilindro retornou | Lista |
| `ATUADOR_CONFIG_ALTERADA` | INFO | Tempo do cilindro mudou | Lista |
| `ATUADOR_FALHA` / `FALHA_ATUACAO` | ERRO | Falha ao acionar | Alerta vermelho |
| `FILA_CHEIA` | AVISO | Fila de inspeção cheia | Alerta amarelo |

---

### 2.9 Tela de esteiras (cadastro)

Mostra as esteiras cadastradas. Operador só consulta. Admin cadastra, edita e remove.

**Listar:** `GET /api/esteiras`
- Resposta: lista de:
  ```json
  { "id": 1, "nomeIdentificador": "Linha Principal 01", "velocidadeAtual": 0, "status": "ATIVA", "dataAlteracao": "2026-11-26T08:00:00" }
  ```

**Buscar uma:** `GET /api/esteiras/{id}`

**Criar (ADMIN):** `POST /api/esteiras`
- Envia `{ "nomeIdentificador": "Linha 2", "velocidadeAtual": 0, "status": "ATIVA" }`
- `status` aceita `ATIVA`, `PAUSADA` ou `MANUTENCAO`.
- Resposta: `201 Created` com a esteira criada.

**Editar (ADMIN):** `PATCH /api/esteiras/{id}`
- Envia os campos que mudaram, no mesmo formato da criação.

**Remover (ADMIN):** `DELETE /api/esteiras/{id}`
- Resposta `200` sem corpo.
- Pode falhar com **409** se houver produção ou peças recusadas ligadas à esteira.

---

### 2.10 Tela de usuários (somente ADMIN)

**Listar:** `GET /api/usuarios`

**Buscar um:** `GET /api/usuarios/{id}`

**Criar:** `POST /api/usuarios`
- Envia:
  ```json
  { "nome": "Operador 1", "email": "op1@local", "senha": "senha123", "perfil": "OPERADOR" }
  ```
- `perfil`: `ADMIN` ou `OPERADOR`.

**Editar:** `PATCH /api/usuarios/{id}`
- Mesmo formato da criação.

**Remover:** `DELETE /api/usuarios/{id}`
- O backend recebe o id de quem está removendo e impede a remoção da própria conta.

Esta tela não deve aparecer para OPERADOR.

---

### 2.11 Tela de simulação (somente ADMIN, uso de teste)

Só deve aparecer se o app for usado com a ESP simulada (`esp.simulado=true`).

- `POST /api/simulacao/sensor?ativo=true` (ou `false`): define o sensor simulado.
- `POST /api/simulacao/sensor/pulso?ms=300`: simula um pulso no sensor.
- Não use em produção.

---

## 3. Regras de comportamento do app

### 3.1 Perfis na interface

- Se `usuario.perfil` for `OPERADOR`, esconder: criar, editar e remover esteiras; iniciar produção; tela de usuários; tela de simulação; botão "Testar cilindro".
- O backend já bloqueia essas ações. Esconder na tela é só para não mostrar botões que vão falhar.

### 3.2 Permissões por rota

| Rota | OPERADOR | ADMIN |
|---|---|---|
| `POST /api/auth/login` | Sim (público) | Sim |
| `GET /api/auth/eu`, `POST /api/auth/senha` | Sim | Sim |
| `GET /api/inspecao/*`, `POST /api/inspecao/*` | Sim | Sim |
| `GET /api/atuador/*` | Sim | Sim |
| `POST /api/atuador/acionar`, `PUT /api/atuador/configuracao` | Sim (backend permite) | Sim |
| `GET /api/pecas-recusadas/*` | Sim | Sim |
| `GET /api/eventos*` | Sim | Sim |
| `GET /api/producao/*` | Sim | Sim |
| `POST /api/producao/*` | Não (403) | Sim |
| `GET /api/esteiras/*` | Sim | Sim |
| `POST`, `PATCH`, `DELETE /api/esteiras/*` | Não (403) | Sim |
| `/api/usuarios/**` | Não (403) | Sim |
| `/api/simulacao/**` | Não (403) | Sim |

> **Observação:** hoje o backend permite que OPERADOR acione o cilindro manualmente (`/api/atuador/acionar`). Se isso não for desejado, é uma mudança no backend, a ser combinada.

### 3.3 Ordem recomendada para iniciar a operação

1. Login → `POST /api/auth/login`
2. Verificar equipamentos → `GET /api/inspecao/verificacao`
3. Se `tudoOk: true`, mostrar "Iniciar inspeção"
4. Confirmação do usuário → `POST /api/inspecao/iniciar?confirmar=true`
5. Acompanhar com `GET /api/inspecao/coleta` (a cada 2 s) e `GET /api/eventos/stream` ou polling
6. Parar → `POST /api/inspecao/parar`

### 3.4 Atualização da tela

| Dado | Como atualizar |
|---|---|
| Estado da coleta | Polling de `GET /api/inspecao/coleta` a cada 2 s |
| Eventos | Stream `GET /api/eventos/stream` ou polling `GET /api/eventos?depois=` a cada 2 s |
| Produção | `GET /api/producao/esteira/{id}/atual` a cada 5 s |
| Atuador | `GET /api/atuador/status` a cada 5 s |

---

## 4. Itens fora do escopo atual

- **Notificações push:** não existem. O app recebe eventos apenas com a tela aberta (stream ou polling).
- **Relatórios na nuvem:** não há endpoint de nuvem. O backend é local.
- **Logout no servidor:** não existe revogação de token. O logout é só local.
- **Fotos em lote:** não há endpoint de listagem de imagens. Cada foto é buscada por ID.

---

## 5. Checklist para validar o app

- [ ] Login com `ADMIN` e com `OPERADOR`, com token guardado e reaproveitado.
- [ ] Token expirado (ou backend reiniciado) leva de volta ao login.
- [ ] "Verificar equipamentos" mostra os três itens e o `tudoOk`.
- [ ] "Iniciar inspeção" só habilita com `tudoOk: true` e confirmação do usuário.
- [ ] `GET /api/inspecao/coleta` atualiza o estado a cada 2 s.
- [ ] Foto de peça recusada abre com o cabeçalho `Authorization`.
- [ ] Stream de eventos funciona com cabeçalho, ou o polling substitui.
- [ ] Botões de ADMIN somem para OPERADOR, e a API continua recusando (403).
- [ ] "Testar cilindro" pede confirmação antes de acionar.
