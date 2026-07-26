# Contribuir a HyperBrain-core

Gracias por tu interés en HyperBrain-core. Este documento describe cómo se
aceptan contribuciones externas y qué condiciones legales las gobiernan.

> **Resumen en una línea:** aceptamos contribuciones bajo un **CLA obligatorio**;
> el proyecto se distribuye bajo **AGPLv3** ([LICENSE](LICENSE) · [NOTICE](NOTICE)).

---

## 1. Contributor License Agreement (CLA) — obligatorio

**Todo contribuidor externo debe firmar el CLA antes de que su Pull Request pueda
ser aceptado.** No se fusiona ningún PR de un tercero sin la firma registrada.

La firma es automática y sin fricción: al abrir tu primer PR, el bot de
**CLA Assistant** comentará con las instrucciones y registrará tu firma. No hay
papeleo manual. El texto contractual completo vive en **[CLA.md](CLA.md)** (esta
sección es un resumen no vinculante de aquél).

### Qué cedes al firmar

Al firmar el CLA otorgas a **Daniel Caita** (titular del proyecto) una **licencia
amplia, perpetua, mundial, irrevocable y libre de regalías** sobre tu contribución,
que incluye —sin limitarse a— los derechos de **usar, reproducir, modificar,
sublicenciar, distribuir y relicenciar** tu contribución, **incluido el uso
comercial y el relicenciamiento bajo términos distintos de la AGPLv3**.

Esta cesión es lo que permite mantener la flexibilidad de **dual-licensing** del
proyecto (ver [NOTICE](NOTICE) y `LICENSING.md`): el titular puede ofrecer el
código —o módulos específicos de él— bajo AGPLv3 y, adicionalmente, bajo una
licencia comercial, sin tener que renegociar con cada contribuidor.

Conservas la autoría y el derecho a usar tu propia contribución libremente. El
CLA **no** te transfiere obligaciones ni te retira derechos sobre tu propio
trabajo; solo concede al titular la licencia amplia descrita arriba.

---

## 2. Flujo de contribución

1. **Abre o referencia un issue.** Toda contribución debe estar ligada a un issue
   que describa el problema o la mejora. Comenta tu intención de trabajarlo antes
   de invertir esfuerzo, para evitar duplicar trabajo.
2. **Haz fork** del repositorio a tu cuenta.
3. **Crea una rama** descriptiva desde `main`
   (p. ej. `feat/mi-mejora` o `fix/descripcion-corta`).
4. **Implementa el cambio con sus tests.** El código y sus pruebas viajan en la
   misma unidad de trabajo; se espera cobertura de dominio significativa.
5. **Abre el Pull Request** contra `main`, **referenciando el issue**
   (`Closes #NN`) y describiendo el qué y el porqué del cambio.
6. **Firma el CLA** cuando el bot lo solicite (solo la primera vez).
7. **Deja el CI en verde.** El PR solo se revisa con todos los checks pasando:
   - **Build** — `./gradlew build` sin fallos.
   - **Tests** — unitarios e integración (Testcontainers: PostgreSQL + LocalStack).
   - **ArchUnit** — las reglas de aislamiento entre módulos DDD deben respetarse;
     un cambio que cruce una frontera de módulo prohibida no se acepta.

### Convenciones

- **Conventional Commits** en los mensajes (`type(scope): descripción`).
- Un PR, un propósito. No mezcles cambios no relacionados.
- No introduzcas secretos, claves ni `.env` en el historial.

---

## 3. Licencia de las contribuciones

Salvo lo establecido por el CLA (que otorga al titular la licencia amplia
adicional descrita en la sección 1), tu contribución se incorpora al proyecto y
se distribuye bajo la **GNU AGPLv3**, en coherencia con [LICENSE](LICENSE) y
[NOTICE](NOTICE).

Consulta [NOTICE](NOTICE) para entender la estrategia open-core y la posibilidad
de que módulos específicos migren a licenciamiento comercial en el futuro
(sin efecto retroactivo sobre lo ya publicado bajo AGPLv3).

---

## Contacto

¿Dudas sobre el CLA o el proceso? Escribe a **Daniel Caita** —
i7.danielcc@gmail.com.
