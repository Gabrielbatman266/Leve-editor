# Leve Editor

Editor de vídeo leve para Android: abre um filme, corta em cenas (estilo CapCut), dá nome/cor às cenas, monta a ordem final, coloca texto e exporta MP4.

## Como usar
1. **Novo projeto** → escolha o filme (MP4 funciona melhor).
2. **Cortar**: arraste a linha do tempo (pinça = zoom), toque em **[ Início** e **Fim ]**, ajuste pelas bordas brancas e toque em **Salvar cena**. O Início da próxima cena já fica no Fim da anterior.
3. **Cenas**: lista em ordem do filme. Toque para assistir, **+** para mandar para a Montagem, **⋮** para renomear/cor, ajustar corte, duplicar ou apagar.
4. **Montagem**: arraste pelo **≡** para mudar a ordem, toque na cena para pôr texto (posição, tamanho, cor, duração). **T Títulos** coloca "Cena 1 – Nome" em todas de uma vez.
5. **Pré-visualizar** e **Exportar vídeo** (480p/720p/1080p). O vídeo vai para *Filmes/LeveEditor*.

As cenas não copiam o vídeo: só guardam início e fim, então não ocupam espaço.

## Download
O APK é gerado automaticamente pelo GitHub Actions a cada push — veja a aba **Releases**.

Feito com Kotlin + AndroidX Media3 (ExoPlayer + Transformer).

## Versão PC (leve, roda até no Windows 7)
Arquivo único: [`pc/LeveEditor-PC.html`](pc/LeveEditor-PC.html) — baixe na aba **Releases** e abra no **Chrome** ou **Firefox**. Não instala nada.

- ✂ **Cortar**: linha do tempo com miniaturas, mini-mapa do filme, zoom (Ctrl+roda), Início/Fim (I/O), prévia travada no corte, repetir.
- 🎬 **Montagem**: faixa de cenas (arraste para reordenar) + faixa de textos (arraste/estique), textos na prévia (mover, girar, tamanho).
- **Textos** iguais ao app: estilos prontos, fonte, cor, contorno, brilho, fundo, sombra, animações de entrada/saída/loop.
- **Atalhos**: Espaço, ←/→ (quadro), Shift+←/→ (1s), I, O, S, T, Del, Ctrl+Z/Y.
- **Exportar**: um clique → baixa o **MP4** pronto, com textos. No Chrome é mais rápido que o tempo real (decodifica/codifica pelo próprio navegador e copia o áudio original, sem dessincronizar). Em navegador sem suporte, grava em tempo real automaticamente.
- **Projeto**: salva sozinho no navegador e em `.leve.json` (mesmo formato do app Android).
