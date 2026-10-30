# Manual de Claudio — asistente personal de voz

Claudio es un asistente de voz con inteligencia artificial (Claude, de Anthropic), inspirado en J.A.R.V.I.S. Habla y escucha en español, recuerda a las personas y, en el teléfono, puede manejar la agenda, los mensajes y las llamadas. Lo desarrolla The DOM Studio Web.

## Dónde funciona

| Versión | Cómo se instala | Qué incluye |
|---|---|---|
| Teléfono Android | APK desde GitHub Releases ("Claudio — última versión") | Todo: voz, activación diciendo "Claudio", asistente del teléfono (agenda, contactos, WhatsApp, SMS, llamadas, alarmas, apps) |
| Windows | Instalador `Claudio-Setup.exe` desde GitHub Releases ("Claudio para Windows") | Círculo flotante sobre cualquier ventana, activación diciendo "Claudio", atajo de teclado, memoria y manuales |
| Navegador (PC o iPhone) | Enlace web de GitHub Pages | Conversación por voz o texto, memoria y manuales |

La clave de API, la memoria y los manuales se guardan en cada dispositivo por separado.

## Primer uso

1. Abrir Claudio y entrar a **⚙ Protocolos**.
2. Pegar la **clave de API de Anthropic** (console.anthropic.com → API Keys). Es obligatoria.
3. Completar **Su nombre** (para que Claudio sepa cuándo habla el dueño) y **¿Cómo debo llamarlo a usted?**.
4. Elegir el **núcleo de IA**: Haiku (rápido y económico, recomendado para el día a día), Sonnet (equilibrado) u Opus (máximo razonamiento).
5. Opcional: contexto personal, voz, velocidad y tono.
6. Tocar **Guardar**.

## Cómo hablarle

- **Tocar el círculo** (o el micrófono) y hablar después del bip.
- **Decir "Claudio"** si la activación por voz está encendida. Conviene hacer una pausa breve antes de la pregunta.
- **Escribir** en el cuadro de texto.
- **En Windows:** Ctrl + Mayús + Espacio desde cualquier programa.
- Para **interrumpirlo** mientras habla, tocar el círculo o el micrófono.
- Con **Conversación continua** activada, vuelve a escuchar solo al terminar de responder. Si no se dice nada en unos segundos, deja de escuchar.

## Ajustes (⚙ Protocolos)

- **Clave de API, núcleo de IA, nombre del asistente, su nombre, cómo llamarlo.**
- **Contexto personal:** datos que Claudio tiene siempre presentes.
- **Voz, velocidad y tono.** Con "Probar voz" se escucha cómo suena.
- **Conversación continua** y **Búsqueda web** (para noticias, clima o precios; cuesta un centavo por búsqueda).
- **Activación por voz:** escuchar el nombre sin internet. **Sensibilidad:** más alta lo escucha mejor pero puede activarse solo; más baja, al revés.
- **Asistente del teléfono** (solo Android): botones "Dar permisos" y "Leer notificaciones".
- **Borrar conversación:** vacía el registro, sin tocar personas ni manuales.

## Personas y memoria (botón de las dos siluetas)

- **Presentar a alguien:** "Claudio, te presento a Laura, mi esposa. Tuteala." Queda guardada su ficha.
- **Cuando habla otra persona:** debe decir "Hola Claudio, te habla Laura". Arriba aparece "Hablando con Laura". Para volver al dueño: "vuelvo yo" o tocar ese cartel. A los 20 minutos sin uso vuelve solo al dueño.
- Claudio **no reconoce voces**: confía en quien dice ser.
- **Datos sueltos:** "acordate de que…" y "olvidate de…".
- Lo que el dueño guardó sobre otra persona es confidencial; solo el dueño puede pedir borrar o cambiar fichas.
- En la pantalla de Personas se ven y se borran las fichas y los datos.

## Manuales

En **Personas → Manuales → Subir manual** se cargan manuales de cualquier app (PDF, TXT o MD). Claudio los consulta solo cuando alguien pregunta cómo funciona esa app y explica paso a paso según el manual; si algo no está, lo dice. Conviene ponerle al archivo un nombre claro, por ejemplo "Hub Comercial X5.pdf".

## Asistente del teléfono (Android)

Ejemplos: "¿Qué tengo mañana?", "Agendame el jueves a las 10 una reunión", "Pasá la reunión de las 16 a las 18", "¿Tengo mensajes nuevos?", "Respondele a Laura que llego en diez minutos", "Mandale un WhatsApp a mi jefe…", "Llamá a Laura", "Prepará un mail para…", "Poneme una alarma a las 7", "Temporizador de 20 minutos", "Abrí Spotify", "Llevame a la agencia", "Poné música de…".

Seguridad:
- **Siempre confirma** antes de enviar, llamar o modificar la agenda, y solo actúa con un "sí".
- **Bancos y billeteras bloqueados:** nunca abre ni lee Banco Macro, Galicia, Mercado Pago, Banco del Sol, Pago Fácil, Prex, Brubank, MODO ni ninguna app con "banco", "billetera" o "wallet" en el nombre.
- Con el teléfono **bloqueado** solo responde consultas y la agenda; para lo demás pide desbloquear.
- WhatsApp a un contacto nuevo abre el chat con el mensaje escrito; el usuario toca enviar. Las respuestas desde notificaciones se envían directo.

Instalación en Android: Play Protect puede bloquear la APK por el permiso de leer notificaciones. Se instala desde la PC con `adb install -r Claudio.apk`, o desactivando Play Protect solo durante la instalación y volviéndolo a activar. Si un permiso aparece gris: Ajustes → Apps → Claudio → ⋮ → "Permitir configuración restringida".

Para que la escucha del nombre no se duerma: Ajustes → Apps → Claudio → Batería → "Sin restricciones", y desactivar "Administrar la app si no se usa". La notificación indica el estado: "Claudio está atento" o "Claudio está conversando". Después de reiniciar el teléfono hay que abrir Claudio una vez.

## Claudio para Windows

- Queda como un **círculo flotante** sobre todas las ventanas. Se arrastra desde el anillo exterior a cualquier lugar de la pantalla y recuerda la posición.
- **Tocar el centro** del círculo: hablar.
- **Botón que aparece al pasar el mouse** (esquina del círculo): abre el panel completo. En el panel, el primer botón de arriba lo vuelve a reducir a círculo.
- **Ícono junto al reloj de Windows:** abrir panel, hablar, ocultar o mostrar, "Iniciar con Windows" y salir.
- **Atajo global:** Ctrl + Mayús + Espacio.
- Arranca solo al encender la PC (se puede desactivar desde el ícono).
- El reconocimiento de voz funciona **sin internet** con un modelo en español incorporado; la IA sí necesita conexión.
- Instalación: Windows puede mostrar "Windows protegió su PC" porque la app no tiene firma comercial: tocar **Más información → Ejecutar de todas formas**. Si no escucha, revisar Configuración → Privacidad → Micrófono → permitir apps de escritorio.

### Que no se active con cualquier charla (Windows y teléfono)
- **Después de responder, en el teléfono** Claudio sigue escuchando sin límite de repreguntas (conversación continua real). La charla termina cuando se hace silencio (reintenta una vez antes de cortar), cuando se dice una frase de cierre o al tocar el círculo; entonces vuelve a esperar su nombre.
- **En Windows** escucha como máximo **2 repreguntas seguidas**, con una ventana corta (5 segundos), para no engancharse con cualquier conversación de fondo.
- **Cerrar la charla:** decirle «gracias», «listo», «chau», «eso es todo» o «silencio» corta la conversación sin respuesta y sin seguir escuchando.
- **Apagar la escucha por nombre:** decirle «dejá de escuchar» (o «no me escuches»), o desmarcar **Escuchar mi nombre** en el menú del ícono junto al reloj. Para llamarlo después: atajo Ctrl + Mayús + Espacio o tocar el círculo. Se vuelve a activar marcando de nuevo esa opción.
- **Detección del nombre más estricta (Windows):** el nombre tiene que decirse al comienzo de la frase, bien pronunciado y con buena confianza; palabras parecidas como «claro» o «audio» ya no lo activan.
- **Si todavía se activa solo:** subir el valor de **Sensibilidad** en Protocolos lo vuelve más exigente (menos falsos avisos). Si no lo escucha a usted, bajarlo un poco.
- **Cuidado en reuniones o videollamadas:** si hay gente hablando cerca, conviene desmarcar **Escuchar mi nombre**.

## Modo reunión (solo teléfono Android)

Graba una reunión en segundo plano, solo audio, en archivos de unos 25 minutos guardados en Música > Claudio Reuniones, para transcribirlos después con Gemini. Se inicia diciendo "Claudio, empezá la reunión" o con el botón del círculo con punto. Mientras graba, Claudio no escucha ni habla; se termina desde la notificación, la barra roja o el panel. No graba llamadas ni videollamadas. Ver el manual aparte "Manual Modo Reunion".

## Mensajes de error frecuentes

- **Se agotó el crédito:** cargar saldo en la consola de Anthropic (Billing).
- **La clave de API no es válida:** revisarla en Protocolos.
- **La conexión está muy lenta o se cortó:** reintentar o pasar a Wi-Fi.
- **Falla la búsqueda web:** desactivarla en Protocolos.
- Debajo de cada error aparece un "Detalle técnico" útil para soporte.

## Costos de uso (referencia)

Con el núcleo Haiku, cada interacción cuesta menos de un décimo de centavo de dólar; con uso intenso, menos de un dólar por mes. Sonnet cuesta unas 20 veces más y Opus unas 40.

## Juntar archivos en una sola carpeta (APK)

Claudio puede juntar en una carpeta los archivos de un tema (por ejemplo FINANCO y DOMINO de las últimas 3 semanas), quedarse con la última versión y limpiar los lugares viejos. Se le pide por voz: "Claudio, juntá todo lo de FINANCO y DOMINO de las últimas tres semanas".

**Primera vez:** Android abre la pantalla "Acceso a todos los archivos". Hay que activar Claudio y volver a la app.

**Paso a paso (siempre con tu confirmación):**
1. **Buscar** (no toca nada). Busca por el *nombre* del archivo y la fecha de modificación (no mira el contenido). Cuenta cuántos encuentra, cuántos son repetidos y cuántas son versiones anteriores.
2. **Copiar.** Crea `Documentos/FINANCO-DOMINO` y copia ahí lo que se conserva. Cada copia se compara con el original. Los originales siguen en su lugar.
3. **Limpiar.** Solo si la copia salió perfecta y decís que sí, los originales y los repetidos se mueven a `Documentos/_papelera_claudio`. Quedan 7 días y después se borran solos.
4. **Deshacer.** "Claudio, restaurá los archivos" los devuelve a su lugar original (último lote).

**Cómo elige la última versión:** si dos archivos son idénticos, deja uno. Si tienen nombres de la misma familia (`GUIA_v2`, `GUIA (3)`, `GUIA_final`) y contenido distinto, deja el más nuevo. Si son muy distintos en tamaño, los marca como dudosos y te pregunta; no los toca.

**Límites:** no busca dentro del contenido de los archivos, no entra a carpetas privadas de otras apps (Android/data) y nunca toca apps bancarias.

## Cámara: fotos y videos directos (APK)
Claudio puede sacar fotos y grabar videos **sin abrir la app de cámara**. Todo queda en la galería, en la carpeta **Claudio** (fotos en Pictures/Claudio, videos en Movies/Claudio).
- **Foto:** "Claudio, sacame una foto", "una selfie", "una foto en 5 segundos", "con flash". Por defecto usa la cámara trasera; "selfie" o "de frente" usa la frontal.
- **Video:** "Claudio, grabá un video". Para terminar: tocar el círculo y decir "detener video". Corta solo a los 10 minutos si se olvida.
- **Video con tiempo:** "grabá un video de 30 segundos": se corta solo y Claudio avisa al terminar.
- **Mientras graba, Claudio no habla** (para que su voz no quede en el video): ni antes ni después de empezar. Recién habla al terminar.
- La primera vez Android pide permiso de cámara (y micrófono para el sonido del video); después hay que repetir el pedido.
- **Siempre suena el obturador y Android muestra el indicador de cámara en uso**; Claudio no lo oculta.
- **Claudio tiene que estar abierto en pantalla.** Android no deja usar la cámara desde segundo plano ni con la pantalla apagada.
- Mientras graba un video no escucha su nombre; al detener el video vuelve a escucharlo.
- Claudio no ve las fotos: confirma que las sacó, pero no las describe.

## La imagen de Claudio
En pantalla Claudio se muestra con su imagen holográfica animada: el anillo azul gira alrededor de la carita cuando escucha (más rápido) o habla (la carita late con la voz). Al pensar, el anillo se vuelve dorado, gira como una moneda y se convierte en una esfera de alambre con ojos y órbitas doradas girando; en rojo si hubo un error.
