# welcome-home

App web privada donde varios propietarios conectan su cuenta Nuki y generan enlaces únicos, con rango de fechas, que abren la puerta sin app ni códigos.

## Requisitos

- JDK 25
- Docker (para los tests con Testcontainers)

## Uso

```sh
./gradlew build          # compila y ejecuta los tests (levanta Postgres con Testcontainers)
docker compose up -d     # Postgres de desarrollo en localhost:5432
./gradlew bootRun        # arranca la app con el perfil dev en http://localhost:8080
```

Comprobación rápida: `curl http://localhost:8080/actuator/health`.

## Base de datos

`docker/db-init/01-create-roles.sh` crea dos roles al iniciar un Postgres vacío:

- `migrator`: propietario de la base de datos y del esquema; con él se ejecutan las migraciones.
- `app_user`: el usuario de la app; sin propiedad ni `BYPASSRLS`, solo recibe los permisos que le dan las migraciones.

El script solo se ejecuta con el volumen vacío. Para recrear la base de datos de desarrollo: `docker compose down -v`.

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
