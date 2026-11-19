"""
E5: tira as pastas debug/ de dentro dos datasets de treino.

As pastas debug/ (overlays com o circulo verde) ficam dentro de aprovada_refinada/ e
reprovada_refinada/. Elas nao devem entrar no treino. Este script MOVE (nao apaga) essas
pastas para dados/_debug_fora_do_treino/.

Padrao: so mostra o que faria. Use --executar para mover de fato.

Uso (Windows, dentro de ia-docker\\treinamento):
    py limpar_dataset.py --dados ..\\dados
    py limpar_dataset.py --dados ..\\dados --executar
"""
import argparse
import shutil
from pathlib import Path

PASTAS_REFINADAS = ["aprovada_refinada", "reprovada_refinada"]


def main(argv=None):
    ap = argparse.ArgumentParser(description="Move debug/ para fora dos datasets de treino.")
    ap.add_argument("--dados", default="../dados")
    ap.add_argument("--executar", action="store_true", help="move de verdade (sem isso, so simula)")
    args = ap.parse_args(argv)

    dados = Path(args.dados).resolve()
    destino_raiz = dados / "_debug_fora_do_treino"
    encontrados = 0
    for nome in PASTAS_REFINADAS:
        debug = dados / nome / "debug"
        if not debug.is_dir():
            continue
        quantidade = sum(1 for p in debug.iterdir() if p.is_file())
        encontrados += quantidade
        destino = destino_raiz / nome
        print(f"{debug}  ->  {destino}  ({quantidade} arquivos)")
        if args.executar:
            destino.mkdir(parents=True, exist_ok=True)
            final = destino / "debug"
            if final.exists():
                raise SystemExit(f"Ja existe {final}. Remova ou renomeie antes de executar.")
            shutil.move(str(debug), str(final))

    if encontrados == 0:
        print("Nenhuma pasta debug/ encontrada nos datasets.")
    elif not args.executar:
        print("\nSimulacao. Rode de novo com --executar para mover.")
    else:
        print("\nConcluido. Confira visualmente as imagens antes de treinar.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
