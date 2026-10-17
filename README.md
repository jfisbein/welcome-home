# welcome-home

App web privada donde varios propietarios conectan su cuenta Nuki y generan enlaces únicos, con rango de fechas, que abren la puerta sin app ni códigos.

## Requisitos

- JDK 25
- Docker (para los tests con Testcontainers)

## Uso

```sh
./gradlew build      # compila y ejecuta los tests
./gradlew bootRun    # arranca la app en http://localhost:8080
```

Comprobación rápida: `curl http://localhost:8080/actuator/health`.

## Módulos

Paquetes bajo `welcomehome`, verificados con Spring Modulith (`ModularityTests`):

| Módulo   | Responsabilidad                                              |
|----------|--------------------------------------------------------------|
| `auth`   | Registro y login de propietarios con passkeys                |
| `admin`  | Invitaciones y listado de propietarios                       |
| `crypto` | Keysets, cifrado del API token de Nuki y firma de enlaces    |
| `nuki`   | Cliente de la Nuki Web API detrás de `LockProvider`          |
| `locks`  | Cerraduras: sincronización, configuración y estado           |
| `links`  | Enlaces de acceso: crear, revocar, regenerar y editar        |
| `access` | Flujo del invitado: valida el enlace y abre la puerta        |
| `audit`  | Eventos de acceso y su retención                             |
