# Migração do backend para o Raspberry Pi 4

Este guia descreve como passar o **backend Spring, a câmera e a ESP8266** para um Raspberry Pi 4, mantendo a **API de IA no Windows** (Docker). Ele separa o que muda só por configuração (variáveis de ambiente) do que exigiria alterar código.

> **Status:** o caminho ainda não foi executado no Pi. Os pontos marcados como **a validar** precisam de teste antes de contar com eles (seção 10).

---

## 0. Arquitetura alvo

```
┌──────────────── Raspberry Pi 4 ────────────────┐         ┌──────── Windows (PC) ────────┐
│ Backend Spring (Java 21)                       │  HTTP   │ API de IA (Docker, porta 8000)│
│ PostgreSQL (container arm64)                   │ ──────► │ modelo_producao.tflite        │
│ Câmera USB  (/dev/video0, ffmpeg v4l2)         │   LAN   └───────────────────────────────┘
│ ESP8266     (/dev/ttyUSB0, 115200 bps)         │
└───────────────┬────────────────────────────────┘
                ▼
        ESP8266 ──► cilindro (recusa a peça)
```

| Parte | Hoje (Windows) | Alvo (Raspberry Pi) |
|---|---|---|
| Backend Spring | `mvnw.cmd spring-boot:run` | `java -jar` (ou serviço systemd) |
| PostgreSQL | container Docker | container Docker arm64 (mesmo comando) |
| Câmera | DirectShow, `GENERAL WEBCAM` | v4l2, `/dev/video0` |
| ESP8266 | COM7 | `/dev/ttyUSB0` (confirmar) |
| API de IA | Docker no Windows | **continua no Windows** |
| Firmware da ESP | `mesa-de-lorenzo` | **sem mudança** |

---

## 1. O que muda no código

### 1.1 Obrigatório: nada

O backend já escolhe o comportamento por configuração. Os pontos que dependem de sistema operacional são:

| Ponto | Onde | Como o Pi resolve |
|---|---|---|
| Formato da câmera (`dshow` ou `v4l2`) | `camera.formato` / `CAMERA_FORMATO` | `CAMERA_FORMATO=v4l2` |
| Dispositivo da câmera | `camera.dispositivo` / `CAMERA_DEVICE` | `CAMERA_DEVICE=/dev/video0` |
| Porta serial da ESP | `esp.porta` / `ESP_PORTA` | `ESP_PORTA=/dev/ttyUSB0` |
| Endereço da IA | `ia.base-url` / `IA_BASE_URL` | `IA_BASE_URL=http://IP_DO_WINDOWS:8000` |
| Resolução da câmera | `camera.resolucao` / `CAMERA_RESOLUCAO` | `CAMERA_RESOLUCAO=640x360` (a webcam deve aceitar essa resolução no Pi, ver seção 4) |

Não há nenhum caminho de código específico do Windows além desses valores. O `EspSerialGateway` usa jSerialComm, que aceita o nome da porta do Linux.

### 1.2 Recomendado (exige sua autorização para alterar o backend)

Para não depender de variáveis longas, a opção mais limpa é um **perfil Spring** para o Pi:

- Criar `src/main/resources/application-raspberry.properties` com os valores da seção 1.1.
- Iniciar com `SPRING_PROFILES_ACTIVE=raspberry`.
- O Windows continua com os padrões atuais.

Outros ajustes pequenos, que eu sugiro fazer junto:

1. **`CameraService`:** a mensagem de erro sempre cita `props.dispositivo()` (`/dev/video0`), mesmo no DirectShow. Trocar pela origem real que está sendo usada.
2. **Padrão de `camera.resolucao`:** o código ainda diz `640x480`, que a sua webcam não aceita. Mudar o padrão para `640x360`.
3. **Comentários de `CameraService` e `application.properties`:** atualizar o que é padrão em cada sistema.

### 1.3 Só se a medição mostrar necessidade

- **Captura por frame:** hoje cada captura abre um processo `ffmpeg` novo. Com o intervalo de 0,3 s da demarcação, isso pode pesar no Pi. Se o `top` mostrar CPU alta ou a tampa passar sem ser capturada, a solução é um stream contínuo do ffmpeg. É uma mudança grande em `CameraService`, então só faço se for necessário.
- **Formato de entrada:** se a câmera saturar o USB com YUYV, adicionar `-input_format mjpeg` no comando do ffmpeg em `CameraService`.

### 1.4 O que não muda

- **Firmware da ESP:** nenhuma alteração. O Pi envia `NOK:<ms>` pela porta serial, como o Windows faz.
- **API de IA:** nenhuma alteração de código. Só precisa aceitar conexões da rede local (seção 5).
- **Banco de dados:** mesmo esquema e mesmas tabelas.

---

## 2. Preparar o Raspberry Pi

### 2.1 Hardware

- Raspberry Pi 4 (4 GB ou mais recomendado).
- Cartão microSD de 32 GB ou mais, ou SSD USB. Para o banco de dados, o SSD é mais confiável.
- Fonte oficial de 5 V / 3 A. Fonte fraca causa queda de tensão, e a câmera e a ESP podem falhar sem aviso.
- Rede por cabo Ethernet (recomendado) ou Wi-Fi.
- Webcam USB, cabo USB da ESP8266 e a fiação do cilindro (a mesma de hoje).

### 2.2 Sistema operacional

Instale o **Raspberry Pi OS de 64 bits** (com Desktop ou Lite, tanto faz). Confirme a arquitetura:

```bash
uname -m
```

Deve aparecer `aarch64`. Se aparecer `armv7l`, o sistema é de 32 bits (ver seção 10).

Atualize o sistema:

```bash
sudo apt update && sudo apt full-upgrade -y
```

### 2.3 Pacotes

```bash
sudo apt install -y git ffmpeg v4l-utils
```

- `ffmpeg`: captura da câmera.
- `v4l-utils`: comando `v4l2-ctl`, para ver as resoluções da câmera.

**Java 21.** Verifique se o pacote existe:

```bash
apt-cache policy openjdk-21-jdk
```

- Se aparecer uma versão instalável, instale com `sudo apt install -y openjdk-21-jdk`.
- Se não aparecer, instale o **Temurin 21 aarch64** pelo site do Adoptium e defina `JAVA_HOME`.

Confirme:

```bash
java -version
```

Deve mostrar `21`.

**Docker** (para o PostgreSQL):

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Saia e entre de novo na sessão para o grupo valer.

### 2.4 Permissões para câmera e porta serial

```bash
sudo usermod -aG dialout,video $USER
```

Saia e entre de novo. Confira:

```bash
groups
```

Deve aparecer `dialout` e `video`.

---

## 3. Banco de dados (PostgreSQL)

O mesmo comando do Windows funciona no Pi, porque a imagem `postgres:16` tem versão arm64:

```bash
docker run -d --name pg-inspecao --restart unless-stopped \
  -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=inspecao postgres:16
```

**Teste:**

```bash
docker exec -it pg-inspecao psql -U postgres -d inspecao -c "select 1;"
```

Deve retornar `1`.

---

## 4. Câmera USB no Pi

### 4.1 Descobrir o dispositivo

```bash
ls -l /dev/video*
```

Webcams USB costumam criar **dois** nós (`/dev/video0` e `/dev/video1`), e só um deles captura imagem. Use o comando abaixo para ver qual é:

```bash
v4l2-ctl -d /dev/video0 --list-formats-ext
```

- Se aparecerem formatos `MJPG` ou `YUYV` com tamanhos, esse é o nó de captura.
- Se aparecer só o formato de metadados, teste `/dev/video1`.

### 4.2 Confirmar a resolução

Procure `640x360` na lista. Se não existir, escolha outra resolução (ex.: `1280x720`) e ajuste `CAMERA_RESOLUCAO`.

### 4.3 Testar a captura

```bash
ffmpeg -f v4l2 -video_size 640x360 -i /dev/video0 -frames:v 1 teste.jpg
```

Deve gerar o `teste.jpg`. Abra e confira o enquadramento.

Se der erro `Could not set video options`, a resolução não é suportada. Troque pela que apareceu no `v4l2-ctl`. Se der `Device or resource busy`, outro programa está usando a câmera.

> A câmera do Pi **não** é a mesma do Windows. O nome `GENERAL WEBCAM` não existe no Linux. O que muda é o dispositivo (`/dev/videoN`), e o enquadramento pode mudar se a câmera for remontada.

---

## 5. API de IA no Windows

A IA continua no Docker do PC. O Pi precisa alcançá-la pela rede local.

### 5.1 Endereço fixo

Dê um **IP fixo** ao PC Windows. O jeito mais simples é uma reserva de DHCP no roteador, ligando o endereço MAC do PC a um IP. Anote o IP (ex.: `192.168.0.50`).

### 5.2 Liberar a porta 8000 no firewall

No PowerShell, como administrador:

```powershell
New-NetFirewallRule -DisplayName "IA inspecao 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

O perfil `Private` vale para redes marcadas como privadas. Se a rede do PC estiver como pública, mude para privada em Configurações > Rede.

### 5.3 Conferir que a porta está exposta

Com a IA no ar (`docker compose up -d api`):

```powershell
netstat -ano | findstr :8000
```

Deve aparecer `0.0.0.0:8000` (ou o IP da rede). Se aparecer só `127.0.0.1:8000`, o Pi não consegue chegar lá.

### 5.4 Testar do Pi

```bash
curl -s http://192.168.0.50:8000/health
```

Deve responder `"status":"ok"` e `"model_loaded":true`. Se não responder, o problema é firewall ou IP, não o backend.

---

## 6. ESP8266 no Pi

### 6.1 Descobrir a porta

Conecte a ESP por USB ao Pi:

```bash
ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null
dmesg | tail -n 20
```

O normal é `/dev/ttyUSB0` (chips CP2102 e CH340). Se aparecer `/dev/ttyACM0`, use essa.

### 6.2 Conferir a comunicação

Abra o monitor serial a 115200 bps:

```bash
sudo apt install -y screen
screen /dev/ttyUSB0 115200
```

Ao abrir, a ESP reinicia (é o comportamento do DTR). Deve aparecer `Gateway IoT ESP8266 - saidas inicializadas desabilitadas`. Digite `NOK:1000` e Enter: deve acionar a saída por 1 s. Para sair do `screen`: `Ctrl+A` e depois `K`, confirmando com `y`.

> Só um programa pode usar a porta por vez. Feche o `screen` antes de subir o backend.

### 6.3 Firmware

O firmware não muda. Se precisar regravar, faça pelo Windows (PlatformIO, como no guia principal) ou instale o PlatformIO CLI no Pi (`pip install platformio`) e use `pio run -t upload` na pasta `mesa-de-lorenzo/mesa-de-lorenzo`.

---

## 7. Subir o backend no Pi

### 7.1 Clonar o repositório

```bash
cd ~
git clone https://github.com/joaoVictor-Soares/Sistema_Automatizado_de_Inspecao_de_Pecas_com_IA_e_IoT
cd Sistema_Automatizado_de_Inspecao_de_Pecas_com_IA_e_IoT
git checkout feat/ajustes-handoff
cd backend-spring-completo/backend-spring/demo
chmod +x mvnw
```

### 7.2 Variáveis de ambiente

Crie `~/inspecao.env` (um par `CHAVE=valor` por linha, sem aspas):

```
CAMERA_FORMATO=v4l2
CAMERA_DEVICE=/dev/video0
CAMERA_RESOLUCAO=640x360
ESP_PORTA=/dev/ttyUSB0
IA_BASE_URL=http://192.168.0.50:8000
FFMPEG_PATH=ffmpeg
DB_URL=jdbc:postgresql://localhost:5432/inspecao
DB_USER=postgres
DB_PASSWORD=postgres
ADMIN_SENHA=troque-esta-senha
SEGURANCA_SEGREDO=troque-por-uma-chave-com-pelo-menos-32-caracteres
```

Carregue no shell para testar:

```bash
set -a; source ~/inspecao.env; set +a
```

### 7.3 Compilar e rodar

Compilar uma vez (no Pi demora mais do que no PC):

```bash
./mvnw -DskipTests package
```

Rodar:

```bash
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

Na primeira subida, confirme no log:

- `Conectado ao ESP em /dev/ttyUSB0`
- Um usuário `admin@local` criado (com `ADMIN_SENHA` definida, não aparece senha no log)

Teste:

```bash
curl -s http://localhost:8080/actuator/health
```

Deve responder `{"status":"UP"}`.

### 7.4 Iniciar automaticamente (opcional)

Crie `/etc/systemd/system/inspecao.service`:

```ini
[Unit]
Description=Backend de inspecao de tampinhas
After=network-online.target docker.service
Wants=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/Sistema_Automatizado_de_Inspecao_de_Pecas_com_IA_e_IoT/backend-spring-completo/backend-spring/demo
EnvironmentFile=/home/pi/inspecao.env
ExecStart=/usr/bin/java -jar target/demo-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Ajuste `User` e os caminhos para o seu usuário. Depois:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now inspecao
journalctl -u inspecao -f
```

Use `which java` se o caminho do Java for diferente de `/usr/bin/java`.

---

## 8. Verificação em ordem (no Pi)

Com o token obtido por `POST /api/auth/login` em `TOKEN`:

| # | Teste | Resultado esperado |
|---|---|---|
| 1 | `curl http://192.168.0.50:8000/health` | `model_loaded: true` |
| 2 | `curl http://localhost:8080/actuator/health` | `UP` |
| 3 | `curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inspecao/verificacao` | `camera`, `esp` e `ia` com `ok: true`, `tudoOk: true` |
| 4 | `curl -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/inspecao/analisar?atuar=false" -F "file=@foto.png"` | `status` e `confianca` (sem acionar nada) |
| 5 | `curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/atuador/acionar` | Cilindro aciona pelo tempo configurado |
| 6 | `curl -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/inspecao/iniciar?confirmar=true"` | `MONITORANDO` no `GET /api/inspecao/coleta` |

Só siga para a coleta automática depois do item 5.

---

## 9. Calibração no Pi

Mesmo roteiro da seção 10 do guia principal, com estas diferenças:

- **Demarcação:** a câmera pode ter outro ângulo. Refaça `DEMARCACAO_X`, `_Y`, `_LARGURA` e `_ALTURA`.
- **Atraso até o cilindro:** a IA agora fica na rede. Meça a latência com `curl -w "%{time_total}\n" -o /dev/null -F "file=@foto.png" http://192.168.0.50:8000/predict` e ajuste `INSPECAO_ATRASO_ATUACAO` com folga.
- **Carga:** com o backend rodando, veja `top` e `vcgencmd get_throttled`. Qualquer valor diferente de `0x0` indica falta de energia ou superaquecimento.

---

## 10. Riscos e pontos a validar

| # | Ponto | Por que importa | Como verificar |
|---|---|---|---|
| R1 | **Biblioteca serial no Pi (a validar)** | A jSerialComm 2.11.0 precisa de um binário nativo para Linux ARM64. Não consegui confirmar que o JAR traz essa versão | Subir o backend e ver se o log mostra `Conectado ao ESP em /dev/ttyUSB0`. Se aparecer `UnsatisfiedLinkError`, me avise: as saídas são outra biblioteca serial ou o Pi OS de 32 bits |
| R2 | **Captura por frame** | Cada captura abre um `ffmpeg`. Pode pesar no Pi e perder tampas | Olhar CPU no `top` durante a coleta e ver se as tampas são todas detectadas |
| R3 | **Falha da IA ou da rede** | Com `inspecao.falha-reprova=true`, se o Pi não alcançar a IA, todas as peças são recusadas | Monitorar `GET /api/inspecao/coleta` e o log. Antes de ligar a esteira, o teste 1 da seção 8 deve estar verde |
| R4 | **Energia** | Fonte fraca causa queda de tensão e resets | `vcgencmd get_throttled` durante o uso |
| R5 | **Relógio** | Logs e registros de peças usam a hora do sistema | `timedatectl` deve mostrar a hora certa (o Pi usa NTP quando há rede) |
| R6 | **Dois nós de vídeo** | Usar o nó errado dá erro sem explicação clara | Seção 4.1 |

---

## 11. Checklist de migração

1. [ ] Pi com Raspberry Pi OS 64 bits (`uname -m` = `aarch64`).
2. [ ] `ffmpeg`, `v4l-utils`, Java 21 e Docker instalados.
3. [ ] Usuário nos grupos `dialout` e `video`.
4. [ ] Câmera com `ffmpeg -f v4l2 ... -frames:v 1 teste.jpg` funcionando.
5. [ ] ESP em `/dev/ttyUSB0` respondendo a `NOK:1000` pelo `screen`.
6. [ ] PostgreSQL no container arm64 com `select 1` ok.
7. [ ] PC Windows com IP fixo, firewall liberado e `netstat` mostrando `0.0.0.0:8000`.
8. [ ] `curl http://IP_DO_WINDOWS:8000/health` do Pi respondendo `model_loaded: true`.
9. [ ] `~/inspecao.env` preenchido.
10. [ ] Backend compilado e rodando, `actuator/health` UP.
11. [ ] Verificação (`/api/inspecao/verificacao`) com `tudoOk: true`.
12. [ ] Teste de acionamento manual do cilindro.
13. [ ] Calibração da demarcação e do atraso.
14. [ ] Serviço systemd habilitado (opcional).
