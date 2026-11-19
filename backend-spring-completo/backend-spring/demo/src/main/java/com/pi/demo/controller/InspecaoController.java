package com.pi.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pi.demo.dto.InspecaoStatusDto;
import com.pi.demo.dto.ResultadoInspecaoDto;
import com.pi.demo.dto.VerificacaoDto;
import com.pi.demo.security.OperadorResolver;
import com.pi.demo.service.ColetaAutomaticaService;
import com.pi.demo.service.InspecaoOrchestratorService;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/inspecao")
public class InspecaoController {

    private final InspecaoOrchestratorService orquestrador;
    private final ColetaAutomaticaService coleta;

    public InspecaoController(InspecaoOrchestratorService orquestrador, ColetaAutomaticaService coleta) {
        this.orquestrador = orquestrador;
        this.coleta = coleta;
    }

    /** Varredura de inicializacao: camera, ESP e API de IA. Nao inicia a coleta. */
    @GetMapping("/verificacao")
    public VerificacaoDto verificacao() {
        return coleta.verificar();
    }

    /** Inicia a coleta automatica depois da confirmacao (confirmar=true). Recusa se algo estiver offline. */
    @PostMapping("/iniciar")
    public VerificacaoDto iniciar(@RequestParam(defaultValue = "false") boolean confirmar) {
        return coleta.iniciar(confirmar);
    }

    /** Encerra a coleta automatica. */
    @PostMapping("/parar")
    public Map<String, Object> parar() {
        coleta.parar();
        return Map.of("estado", coleta.estado());
    }

    /** Estado da coleta automatica (PARADO ou MONITORANDO) e contagem de pecas enviadas a IA. */
    @GetMapping("/coleta")
    public Map<String, Object> estadoColeta() {
        return Map.of("estado", coleta.estado(), "pecasDetectadas", coleta.pecasDetectadas());
    }

    @GetMapping("/status")
    public InspecaoStatusDto status() {
        return orquestrador.status();
    }

    @GetMapping("/ultimo")
    public ResponseEntity<ResultadoInspecaoDto> ultimo() {
        ResultadoInspecaoDto r = orquestrador.ultimoResultado();
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    /**
     * Disparo manual: captura da camera -> IA (main.py) -> (opcional) banco + atuador.
     * atuar=false apenas classifica, sem gravar nem mover o cilindro.
     * Sem operadorId, usa o usuario autenticado.
     */
    @PostMapping("/disparar")
    public ResultadoInspecaoDto disparar(@RequestParam(defaultValue = "true") boolean atuar,
                                         @RequestParam(required = false) Integer operadorId,
                                         Principal principal) {
        return orquestrador.inspecionarAgora(atuar, OperadorResolver.operadorId(principal, operadorId));
    }

    /** Classifica uma imagem enviada (multipart, campo "file"), sem usar a camera. */
    @PostMapping(value = "/analisar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultadoInspecaoDto analisar(@RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "false") boolean atuar,
                                         @RequestParam(required = false) Integer operadorId,
                                         Principal principal) throws IOException {
        return orquestrador.inspecionarImagem(file.getBytes(), atuar, OperadorResolver.operadorId(principal, operadorId));
    }
}
