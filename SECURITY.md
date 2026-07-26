# Política de seguridad — HyperBrain-core

Gracias por ayudar a mantener HyperBrain seguro. Este documento describe cómo
reportar vulnerabilidades de forma responsable.

## Reporte de vulnerabilidades

**No abras un issue público para reportar una vulnerabilidad de seguridad.** Un
issue abierto expone el fallo antes de que exista una corrección.

Usa uno de estos canales privados:

1. **GitHub Security Advisories** — pestaña *Security* → *Report a vulnerability*
   (canal preferido; permite coordinación privada).
2. **Correo** — **i7.danielcc@gmail.com**, con el asunto `[SECURITY] HyperBrain`.

Incluye, en la medida de lo posible:

- una descripción del fallo y su impacto,
- pasos para reproducirlo (o una prueba de concepto),
- la versión / commit afectado,
- cualquier mitigación temporal que conozcas.

## Qué esperar

HyperBrain está en fase MVP y lo mantiene **un único desarrollador**; no hay un
SLA formal, pero el compromiso es:

- **Acuse de recibo** en cuanto sea razonablemente posible tras el reporte.
- **Evaluación** del reporte y comunicación de si se acepta como vulnerabilidad.
- **Divulgación coordinada:** trabajaremos contigo en un plazo razonable antes de
  hacer público el detalle, y se te dará crédito si así lo deseas.

Te pedimos, a cambio, una **divulgación responsable**: dar margen para corregir
antes de publicar, no acceder ni modificar datos que no sean tuyos, y no degradar
el servicio.

## Alcance

Aplica al código de este repositorio (`HyperBrain-core`). Los demás repositorios
del ecosistema (`HyperBrain-Infra`, `HyperBrain-SentinelAPI`, la app iOS) tienen
—o tendrán— su propia política; ante la duda, usa los canales de arriba y lo
enrutamos.

Considera **fuera de alcance** los reportes que dependan de configuraciones
inseguras propias, ingeniería social, o dependencias de terceros ya con parche
disponible (repórtalas aguas arriba).

## Manejo de secretos

Este proyecto **no** versiona secretos: la gestión de credenciales usa SOPS y
variables de entorno fuera del repositorio. Si detectas un secreto filtrado en el
historial, trátalo como vulnerabilidad y repórtalo por los canales privados.
