# Manual del Modo Reunión de Claudio (solo teléfono Android)

El **Modo reunión** graba el audio de una reunión en segundo plano, sin video y con la pantalla apagada, y lo guarda en archivos de audio separados para transcribirlos después con Gemini u otra IA. Es de uso personal y lo desarrolla The DOM Studio Web.

## Qué hace y qué no hace

- **Hace:** graba el micrófono (reuniones presenciales) o el audio que suena en el teléfono, y guarda archivos M4A (AAC) de unos 25 minutos cada uno en **Música > Claudio Reuniones**. Un archivo de 25 minutos pesa unos 9 MB.
- **No transcribe por sí solo.** La transcripción con hablantes (Hablante 1, 2, 3…) se hace pasando los audios a Gemini, a mano.
- **No graba llamadas ni videollamadas.** Android impide que una app grabe la voz de la otra persona en una llamada o en Meet, Zoom o WhatsApp.
- Mientras graba, **Claudio no escucha su nombre ni habla**, para no interrumpir ni cortar la grabación. Al terminar, vuelve a escuchar solo.

## Cómo empezar una reunión

**Con la voz:** decir "Claudio" y después "empezá la reunión" (o "grabá la reunión", "modo reunión"). Claudio confirma en una frase y empieza a grabar apenas termina de responder.

**Con el botón:** tocar el ícono del círculo con punto (arriba a la derecha) → elegir qué grabar → **Empezar a grabar**.

Opciones:
1. **Micrófono (reunión presencial).** Dejar el teléfono sobre la mesa, cerca de todos y con el micrófono despejado.
2. **Audio del teléfono.** Graba lo que suena en el equipo, por ejemplo una nota de voz o un video que se reproduzca. Android pide confirmar "compartir pantalla": se acepta, pero no se graba video. Se debe reproducir el audio completo mientras graba (una hora de audio lleva una hora).
3. **Cortar cada (minutos):** por defecto 25. Los cortes son automáticos y no se pierde audio entre una parte y la siguiente.

## Durante la reunión

- Aparece una **notificación fija** "Grabando reunión" con cronómetro y botón **Terminar**.
- Dentro de la app hay una barra roja arriba con el tiempo y un botón **Terminar**. Tocándola se abre el panel con el cronómetro y una barra de nivel: **si la barra no se mueve, no está entrando audio**.
- Con el micrófono, otra app que use el micrófono (una llamada, por ejemplo) puede dejar la grabación en silencio.
- Conviene desactivar la optimización de batería para Claudio (Ajustes del teléfono > Apps > Claudio > Batería > Sin restricciones), porque algunos teléfonos cierran las apps en segundo plano.

## Terminar y obtener los audios

1. Tocar **Terminar** en la notificación, en la barra roja o en el panel (también se le puede escribir "terminá la reunión" en el cuadro de texto).
2. Claudio avisa "Reunión guardada" y vuelve a escuchar su nombre.
3. En el panel de reunión, **ÚLTIMA REUNIÓN** lista las partes con su duración. **Compartir audios** abre el menú de Android para mandarlos a Gemini, Drive o WhatsApp.
4. Los archivos también están en la app de archivos del teléfono, carpeta **Music/Claudio Reuniones**, y se llaman `Reunion_AAAA-MM-DD_HHMM_parte01.m4a`, `parte02`, etc.

## Cómo transcribirlos con Gemini

Subir las partes **en orden** y pedir, por ejemplo:

> Transcribí este audio completo en español, indicando quién habla como "Hablante 1", "Hablante 2", etc. (sin intentar identificar nombres) y con marcas de tiempo cada tanto. Después agregá: un resumen breve, las decisiones tomadas y las tareas pendientes con responsable, si se mencionan.

Recomendaciones: una parte por vez si Gemini limita la duración; al pegar la siguiente parte, avisar que continúa la misma reunión y que mantenga la numeración de hablantes.

## Problemas frecuentes

- **"No pude iniciar la reunión":** falta el permiso de micrófono o de notificaciones. Aceptarlos cuando Android los pida.
- **Con "Audio del teléfono" la pantalla no se apaga** mientras graba (queda tenue), porque si se apaga la app que reproduce se pausa. Al terminar vuelve a la normalidad. Conviene tener el teléfono enchufado en reuniones largas.
- **La barra de nivel no se mueve con "Audio del teléfono":** la app que reproduce el audio puede bloquear su captura. Probar con el micrófono, acercando el teléfono a la fuente.
- **Se cortó la grabación:** probablemente Android cerró la app por batería. Desactivar la optimización de batería para Claudio. Solo se pierde la parte en curso; las partes anteriores quedan guardadas.
- **No aparece la carpeta:** buscar en la app de archivos del teléfono, dentro de **Música**.

## Privacidad

Los audios quedan solo en el teléfono hasta que el usuario los comparta. Hay que **avisar a las personas** que se está grabando la reunión.
