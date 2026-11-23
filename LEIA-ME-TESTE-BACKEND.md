# Roteiro rápido de testes do backend (Windows)

O passo a passo completo está em `COMO-RODAR-O-SISTEMA.md`. Este arquivo é um roteiro curto para testar cada parte isoladamente, com comandos prontos.

Feche antes de começar: monitor serial do PlatformIO e qualquer programa que use a câmera ou a COM7.

## 1. Subir a IA e o banco

```bat
docker run -d --name pg-inspecao -p 5432:5432 -e POSTGRES_DB=inspecao -e POSTGRES_PASSWORD=postgres postgres:16
cd ia-docker
docker compose up -d --build api
curl.exe http://localhost:8000/health
```

`/health` precisa mostrar `"model_loaded":true`.

## 2. Subir o backend

```bat
cd backend-spring-completo\backend-spring\demo
set ESP_PORTA=COM7
set CAMERA_DSHOW=GENERAL WEBCAM
set CAMERA_RESOLUCAO=640x360
set ADMIN_SENHA=troque-esta-senha
mvnw.cmd spring-boot:run
```

Confirme: `curl.exe http://localhost:8080/actuator/health` responde `{"status":"UP"}`.

## 3. Login

```bat
curl.exe -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"email\":\"admin@local\",\"senha\":\"troque-esta-senha\"}"
```

Use o token nas próximas chamadas: `-H "Authorization: Bearer SEU_TOKEN"`.

## 4. Teste da câmera, da ESP e da IA (sem mover o cilindro)

```bat
curl.exe http://localhost:8080/api/inspecao/verificacao -H "Authorization: Bearer SEU_TOKEN"
```

Os três itens (`camera`, `esp`, `ia`) precisam estar com `ok: true`.

## 5. Classificar uma foto sem acionar o cilindro

```bat
curl.exe -X POST "http://localhost:8080/api/inspecao/analisar?atuar=false" -H "Authorization: Bearer SEU_TOKEN" -F "file=@C:\caminho\foto.png"
```

`atuar=false` só classifica. Nada é acionado e nada é gravado como recusa.

## 6. Captura automática (com a câmera)

```bat
curl.exe -X POST "http://localhost:8080/api/inspecao/iniciar?confirmar=true" -H "Authorization: Bearer SEU_TOKEN"
curl.exe http://localhost:8080/api/inspecao/coleta -H "Authorization: Bearer SEU_TOKEN"
curl.exe -X POST http://localhost:8080/api/inspecao/parar -H "Authorization: Bearer SEU_TOKEN"
```

Passe uma tampa verde pela região da demarcação. O log mostra `Tampa na demarcacao (verde X%): enviando para a IA.`. Se a IA reprovar, aparece `Enviado ao ESP: NOK:<ms>` e depois `[ESP] Saida 0 desacionada`.

## 7. Teste sem câmera (usa uma imagem do disco)

Para testar a coleta sem a webcam, defina antes de subir o backend:

```bat
set CAMERA_IMAGEM_SIMULADA=C:\caminho\pasta_ou_foto.jpg
```

Se for uma pasta, a cada captura o backend sorteia uma imagem dela.

## 8. Teste manual do cilindro

```bat
curl.exe -X POST http://localhost:8080/api/atuador/acionar -H "Authorization: Bearer SEU_TOKEN"
```

Aciona o cilindro pelo tempo configurado. **Aciona de verdade.**

## Atenção

- Nos passos 5 e 6, `atuar` e a coleta automática acionam o cilindro quando a peça é reprovada. Comece sempre com `atuar=false` e a esteira vazia.
- O programa de captura Java (`captura-java\`) é opcional e não é necessário para este roteiro.
