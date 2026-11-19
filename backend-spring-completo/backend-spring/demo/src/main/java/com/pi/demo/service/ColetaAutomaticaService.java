package com.pi.demo.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pi.demo.client.PythonMlClient;
import com.pi.demo.config.DemarcacaoProperties;
import com.pi.demo.dto.VerificacaoDto;
import com.pi.demo.dto.VerificacaoDto.Item;
import com.pi.demo.exception.OperacaoInvalidaException;

/**
 * Coleta automatica de pecas.
 *
 * 1) verificar(): testa camera, ESP e API de IA (sem iniciar nada).
 * 2) iniciar(confirmar=true): so inicia se a verificacao passar. Entao monitora a demarcacao:
 *    quando a tampa entra na regiao (fracao verde acima do limiar), captura o frame e envia
 *    para a IA via InspecaoOrchestratorService. Enquanto a tampa estiver na regiao, nao dispara
 *    de novo (rearma so quando ela sai) e respeita um cooldown.
 * 3) parar(): encerra o monitoramento.
 */
@Service
public class ColetaAutomaticaService {

    private static final Logger log = LoggerFactory.getLogger(ColetaAutomaticaService.class);

    public static final String PARADO = "PARADO";
    public static final String MONITORANDO = "MONITORANDO";

    private final CameraService camera;
    private final EspGateway esp;
    private final PythonMlClient ia;
    private final InspecaoOrchestratorService orquestrador;
    private final EventoService eventos;
    private final DemarcacaoProperties roi;

    private volatile String estado = PARADO;
    private volatile boolean rodando;
    private volatile Thread thread;
    private volatile VerificacaoDto ultimaVerificacao;
    private volatile long pecasDetectadas;

    public ColetaAutomaticaService(CameraService camera,
                                   EspGateway esp,
                                   PythonMlClient ia,
                                   InspecaoOrchestratorService orquestrador,
                                   EventoService eventos,
                                   DemarcacaoProperties roi) {
        this.camera = camera;
        this.esp = esp;
        this.ia = ia;
        this.orquestrador = orquestrador;
        this.eventos = eventos;
        this.roi = roi;
    }

    /** Testa camera, ESP e IA. Nao inicia a coleta. */
    public VerificacaoDto verificar() {
        Item cam = testarCamera();
        Item espItem = esp.conectado()
                ? new Item(true, "ESP conectado (" + esp.porta() + ").")
                : new Item(false, "ESP nao conectado (" + esp.porta() + ").");
        Item iaItem = ia.saudavel()
                ? new Item(true, "API de IA respondendo com modelo carregado.")
                : new Item(false, "API de IA indisponivel ou sem modelo carregado.");
        boolean tudo = cam.ok() && espItem.ok() && iaItem.ok();
        VerificacaoDto v = new VerificacaoDto(cam, espItem, iaItem, tudo);
        ultimaVerificacao = v;
        eventos.publicar("VERIFICACAO", tudo ? EventoService.INFO : EventoService.AVISO,
                tudo ? "Verificacao de inicializacao OK." : "Verificacao de inicializacao com falhas.");
        return v;
    }

    /**
     * Inicia a coleta depois da confirmacao do operador. Recusa se a verificacao falhar.
     */
    public synchronized VerificacaoDto iniciar(boolean confirmar) {
        if (!confirmar) {
            throw new OperacaoInvalidaException("Confirme a inicializacao (confirmar=true).");
        }
        if (rodando) {
            throw new OperacaoInvalidaException("A coleta ja esta em execucao.");
        }
        VerificacaoDto v = verificar();
        if (!v.tudoOk()) {
            throw new OperacaoInvalidaException("Inicializacao recusada: " + resumoFalhas(v));
        }
        rodando = true;
        estado = MONITORANDO;
        pecasDetectadas = 0;
        Thread t = new Thread(this::laco, "coleta-demarcacao");
        t.setDaemon(true);
        thread = t;
        t.start();
        eventos.publicar("COLETA_INICIADA", EventoService.INFO, "Coleta iniciada: aguardando tampas na demarcacao.");
        return v;
    }

    /** Encerra o monitoramento. */
    public synchronized void parar() {
        if (!rodando) {
            return;
        }
        rodando = false;
        estado = PARADO;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        eventos.publicar("COLETA_PARADA", EventoService.INFO, "Coleta parada.");
    }

    public String estado() {
        return estado;
    }

    public long pecasDetectadas() {
        return pecasDetectadas;
    }

    public VerificacaoDto ultimaVerificacao() {
        return ultimaVerificacao;
    }

    // ------------------------------------------------------------------

    private void laco() {
        boolean armado = true;
        long intervaloMs = roi.intervalo().toMillis();
        while (rodando) {
            try {
                byte[] frame = camera.capturar();
                double fracao = DemarcacaoDetector.fracaoVerde(frame, roi);
                if (fracao >= roi.limiarVerde()) {
                    if (armado) {
                        armado = false;
                        pecasDetectadas++;
                        log.info("Tampa na demarcacao (verde {}%): enviando para a IA.",
                                String.format("%.1f", fracao * 100));
                        orquestrador.enfileirarImagem("CAMERA:DEMARCACAO", frame);
                        Thread.sleep(roi.cooldown().toMillis());
                    }
                } else {
                    armado = true;
                }
                Thread.sleep(intervaloMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("Falha no monitoramento da demarcacao: {}", e.getMessage());
                eventos.publicar("COLETA_ERRO", EventoService.ERRO,
                        "Falha no monitoramento: " + e.getMessage());
                try {
                    Thread.sleep(Duration.ofSeconds(1).toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private Item testarCamera() {
        try {
            byte[] frame = camera.capturar();
            return new Item(true, "Camera respondendo (" + frame.length + " bytes).");
        } catch (RuntimeException e) {
            return new Item(false, "Camera indisponivel: " + e.getMessage());
        }
    }

    private static String resumoFalhas(VerificacaoDto v) {
        StringBuilder sb = new StringBuilder();
        if (!v.camera().ok()) sb.append(v.camera().mensagem()).append(' ');
        if (!v.esp().ok()) sb.append(v.esp().mensagem()).append(' ');
        if (!v.ia().ok()) sb.append(v.ia().mensagem()).append(' ');
        return sb.toString().trim();
    }
}
