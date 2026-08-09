# IMPD TV

App de canal único para televisão. Abre já tocando o IMPD, em tela cheia, sem
menu, sem lista, sem configuração. Duas versões nativas:

| Pasta | Aparelho | Tecnologia |
|---|---|---|
| `appletv/` | Apple TV (tvOS 17+) | SwiftUI + AVFoundation |
| `androidtv/` | TV Box com Android TV / Google TV (Android 5+) | Kotlin + Media3/ExoPlayer |

## O desenho

O público é majoritariamente idoso, então há **um único controle para aprender**:
o botão **OK** pausa e volta a assistir. Não existe nada em que se possa entrar,
nada que se possa desconfigurar e nenhuma mensagem de erro para fechar.

- Abre tocando. Nenhuma tela intermediária.
- Faixa grande com **AO VIVO** e o nome do canal aparece a qualquer toque no
  controle e some sozinha em 6 segundos.
- Pausado mostra um cartaz enorme: "Pausado — Aperte OK para voltar a assistir".
- Ao voltar do pause, salta para o ponto ao vivo: ninguém fica assistindo
  minutos atrasado sem perceber.
- Queda de sinal nunca vira erro. Mostra "Sinal caiu — estamos reconectando
  sozinho. Não precisa fazer nada." e tenta de novo com espera crescente até 15s.
- Vigia travamentos: o player pode ficar "esperando dados" para sempre numa
  fonte morta, então uma parada acima de 10 segundos é tratada como queda.
- Tela nunca apaga durante a reprodução.

Tipografia grande e alto contraste em ambas as plataformas, medida para leitura
do outro lado da sala.

## Apple TV

```bash
open appletv/IMPDTV.xcodeproj
```

Selecione o seu Apple TV (ou um simulador tvOS) e rode. Para instalar no
aparelho de verdade é preciso escolher o seu time de assinatura em
*Signing & Capabilities* — é a única coisa que o Xcode precisa de você.

Verificação já feita: compila limpo contra o SDK tvOS 26.5.

## Android TV / Google TV

```bash
cd androidtv
JAVA_HOME=/opt/homebrew/opt/openjdk@17 gradle assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`. Para instalar no box:

```bash
adb connect IP_DA_TV_BOX:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O manifesto declara `LEANBACK_LAUNCHER`, então o app aparece na fileira inicial
do Android TV / Google TV, e marca `leanback` e `touchscreen` como não
obrigatórios — assim instala tanto em TV box quanto em aparelho comum.

## O canal

Um único fluxo HLS, H.264 1080p com AAC, sem DRM e sem token — por isso os dois
apps tocam direto, sem proxy e sem dependência externa. A URL vive em um só
lugar por plataforma:

- `appletv/IMPDTV/Channel.swift`
- `androidtv/app/src/main/java/br/com/impd/tv/Channel.kt`
