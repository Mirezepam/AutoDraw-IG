# AutoDraw para Instagram

App de Android que dibuja automáticamente una imagen con el pincel de las historias de Instagram (o cualquier app de dibujo), trazando sus contornos y sombreado mediante un servicio de accesibilidad. No necesita root ni emulador.

## Descargar
Descarga `AutoDraw.apk` desde la sección **Releases**.

## Uso
1. Instala el APK (permite "orígenes desconocidos" si te lo pide).
2. Abre AutoDraw y elige una imagen: verás la vista previa y el tiempo estimado.
3. Pulsa **Activar servicio de accesibilidad** y activa AutoDraw.
   En Android 13+, si aparece bloqueado: *Ajustes › Apps › AutoDraw › ⋮ › Permitir ajustes restringidos*.
4. En Instagram crea una historia y entra al pincel (elige color y grosor).
5. Botón flotante: **▢** zona de dibujo · **▶** empezar · **⏸** pausar · **⟲** reiniciar · **✥** mover. Bajar volumen también pausa.

## Opciones
- **Imagen:** contornos, detalle, resolución, ignorar trazos cortos, invertir colores.
- **Sombreado:** horizontal / vertical / cruzado, oscuridad, separación de líneas.
- **Posición:** tamaño, posición horizontal y vertical, espejo.
- **Dibujo:** velocidad (1–20), pausa entre trazos, cuenta atrás, longitud máxima de trazo, repetir trazos.

Si Instagram se salta partes: baja la velocidad, sube la pausa o limita la longitud de trazo. No toques la pantalla mientras dibuja.

## Compilar
Requiere el SDK de Android (API 36) y JDK 17+:
```
gradle assembleDebug
```
El APK queda en `app/build/outputs/apk/debug/`.

## Privacidad
El servicio de accesibilidad solo realiza gestos cuando pulsas ▶. No lee el contenido de la pantalla ni envía datos a ningún sitio.
