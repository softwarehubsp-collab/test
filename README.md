# Drive Agenda — Android (código fuente)

Aplicación Android nativa en Kotlin. Permite seleccionar varios archivos mediante el selector de Android, copiar los archivos a almacenamiento privado de la aplicación, iniciar sesión con una cuenta Google, solicitar el permiso Android **Acceso a todos los archivos**, elegir fecha y hora para cada archivo, subir ahora, mantener una lista y transferir a Google Drive con red disponible.

## IMPORTANTE: configuración antes de poder iniciar sesión

1. Instala Android Studio y abre esta carpeta como proyecto.
2. En Google Cloud Console crea un proyecto y **activa Google Drive API**.
3. Configura la **pantalla de consentimiento OAuth** (añade tu cuenta como usuario de prueba si la app está en modo pruebas).
4. Crea credenciales de **cliente OAuth 2.0 tipo Android**, con nombre de paquete `com.driveagenda.app` y huella digital SHA-1 del certificado con el que firmarás la app. Se necesita registrar la SHA-1 de debug para pruebas y la SHA-1 de producción si vas a distribuir APK firmada. No pegues secretos de cliente en el proyecto.
5. Compila en Android Studio con **Build > Build APK(s)** e instala el APK generado. Requiere acceso a Internet, Google Play Services y Android 8+.
6. En el teléfono, inicia sesión con Google, concede el permiso OAuth de Google Drive, selecciona archivos, autoriza acceso a archivos si lo deseas y programa la fecha y hora.

## Detalles

- Google Drive: ámbito OAuth `drive.file`, permite crear archivos en Drive pero no acceder a todo el Drive.
- La opción explícita de permisos abre los ajustes oficiales de Android para **Acceso a todos los archivos** en Android 11+. Para seleccionar documentos **no se requiere** este permiso amplio: se usa el selector del sistema, que es más seguro.
- Cada archivo seleccionado se copia al almacenamiento privado de la app y por eso continúa en la cola. Puede ocupar espacio adicional. No desinstales ni borres los datos de la app antes de subir.
- Al programar se usa alarma exacta cuando Android la permite; si no, se recurre a WorkManager, que no garantiza el minuto exacto. El trabajo requiere Internet y Android puede posponer ejecuciones para conservar batería.
- Las alarmas se reprograman tras reiniciar el móvil. Para las alarmas exactas en Android 12+, el usuario puede conceder **Alarmas y recordatorios** en configuración del sistema. Para máxima fiabilidad, deshabilita optimizaciones restrictivas de batería para la app según el fabricante.
- Tras quitar un archivo de la lista se borra su copia local; cualquier tarea programada que se dispare después no encontrará el elemento y terminará sin subirlo.
- En casos de permisos OAuth adicionales o revocados, puede ser necesario volver a iniciar sesión con Google desde la aplicación; las cargas en segundo plano no pueden mostrar interacciones de consentimiento.

## Estado de entrega

Se entrega el **proyecto fuente**, no un APK ya compilado. No se ha ejecutado una compilación de Android en este entorno y podrían requerirse ajustes menores de dependencias. El inicio de sesión necesita las credenciales OAuth vinculadas a la firma de la aplicación, que deben configurarse con tu propio proyecto Google Cloud.

## Compilación en la nube (sin instalar Android Studio)

El proyecto incluye `.github/workflows/android-apk.yml`. Al subir **el contenido de esta carpeta** a la raíz de un repositorio de GitHub y ejecutar en **Actions > Compilar APK Android > Run workflow**, GitHub Actions instala el SDK y Gradle, compila `app-debug.apk` y lo publica como artefacto `DriveAgenda-APK`. Es un APK de prueba firmado automáticamente, instalable fuera de Google Play. La autenticación Google Drive requiere registrar **el SHA-1 de la firma de ese APK** en Google Cloud OAuth Android para `com.driveagenda.app`; las firmas debug pueden variar entre compilaciones/servidores. Este proyecto no fue compilado ni probado en el entorno de entrega, y el proceso podría revelar errores aún no detectados.
