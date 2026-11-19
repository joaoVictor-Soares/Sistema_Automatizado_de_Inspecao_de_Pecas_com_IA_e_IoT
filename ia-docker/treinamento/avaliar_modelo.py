"""
E6: avalia um modelo .tflite num conjunto de teste SEPARADO do treino.

O conjunto precisa ter as pastas aprovada/ (ou aprovada_refinada/) e reprovada/ (ou
reprovada_refinada/), com fotos que NAO foram usadas no treino. Cada imagem recebe o mesmo
pre-processamento da API (BGR -> RGB, resize 180x180 INTER_AREA, float32 0-255).

Saida: matriz de confusao, acuracia e recall por classe.

Uso (Windows, dentro de ia-docker\\treinamento; Python com ai-edge-litert e opencv):
    py avaliar_modelo.py --modelo ..\\modelos\\candidato.tflite --teste ..\\dados_teste
    py avaliar_modelo.py --modelo ..\\api\\modelo_inspecao.tflite --teste ..\\dados_teste
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:  # fallback para ambientes com TensorFlow
    from tensorflow.lite.python.interpreter import Interpreter

CLASSES = ["aprovadas", "reprovadas"]  # MESMA ordem de api/main.py
PASTAS = {"aprovadas": ["aprovada", "aprovada_refinada"], "reprovadas": ["reprovada", "reprovada_refinada"]}
EXTENSOES = {".png", ".jpg", ".jpeg"}
IMG = 180


def listar(teste: Path):
    caminhos, rotulos = [], []
    for i, classe in enumerate(CLASSES):
        arquivos = []
        for pasta in PASTAS[classe]:
            p = teste / pasta
            if p.is_dir():
                arquivos += [f for f in sorted(p.iterdir()) if f.is_file() and f.suffix.lower() in EXTENSOES]
        print(f"  {classe:<10} {len(arquivos):>5} imagens")
        caminhos += arquivos
        rotulos += [i] * len(arquivos)
    if not caminhos or min(rotulos.count(0), rotulos.count(1)) == 0:
        raise SystemExit("O conjunto de teste precisa ter imagens das duas classes.")
    return caminhos, rotulos


def preparar(caminho: Path) -> np.ndarray:
    dados = np.fromfile(str(caminho), dtype=np.uint8)  # aceita caminho com acento no Windows
    img = cv2.imdecode(dados, cv2.IMREAD_COLOR)
    img = cv2.resize(img, (IMG, IMG), interpolation=cv2.INTER_AREA)
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32)
    return np.expand_dims(rgb, axis=0)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Avalia um .tflite num conjunto de teste separado.")
    ap.add_argument("--modelo", required=True)
    ap.add_argument("--teste", required=True, help="pasta com aprovada(_refinada)/ e reprovada(_refinada)/")
    ap.add_argument("--json", help="opcional: grava o resultado neste arquivo")
    args = ap.parse_args(argv)

    interp = Interpreter(model_path=str(Path(args.modelo).resolve()))
    interp.allocate_tensors()
    entrada = interp.get_input_details()[0]["index"]
    saida = interp.get_output_details()[0]["index"]

    caminhos, rotulos = listar(Path(args.teste).resolve())
    matriz = np.zeros((2, 2), dtype=int)
    for caminho, real in zip(caminhos, rotulos):
        interp.set_tensor(entrada, preparar(caminho))
        interp.invoke()
        previsto = int(np.argmax(interp.get_tensor(saida)[0]))
        matriz[real, previsto] += 1

    acuracia = np.trace(matriz) / matriz.sum()
    recall = {c: (matriz[i, i] / matriz[i].sum() if matriz[i].sum() else 0.0) for i, c in enumerate(CLASSES)}
    print("\nMatriz de confusao (linhas = real, colunas = previsto):")
    print(f"              {CLASSES[0]:>12} {CLASSES[1]:>12}")
    for i, c in enumerate(CLASSES):
        print(f"{c:>12}  {matriz[i, 0]:>12} {matriz[i, 1]:>12}")
    print(f"\nAcuracia: {acuracia:.2%}")
    for c in CLASSES:
        print(f"Recall {c}: {recall[c]:.2%}")

    if args.json:
        Path(args.json).write_text(json.dumps({
            "modelo": args.modelo, "teste": args.teste, "matriz_confusao": matriz.tolist(),
            "acuracia": round(float(acuracia), 4), "recall": {k: round(v, 4) for k, v in recall.items()},
        }, indent=2, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
