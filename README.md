# MeGraS
MeGraS, short for **Me**dia**Gra**ph **S**tore, is the data storage and query processing engine of the MediaGraph project
which aims to elevate multimodal data to first-class citizens in Knowledge-Graphs.
MegraS stores and processes multimodal knowledge graphs including its media components as an RDF graph. 


## Installation
MeGraS is written in [Kotlin](https://kotlinlang.org/) and requires a Java runtime environment of version 8 or higher.
For some operations on audio and video data, [ffmpeg](https://ffmpeg.org/) needs to be installed and added to the system path.

### Building MeGraS from Source
MeGraS uses Gradle as a build system. To build the application, simply run `./gradlew distZip` and unpack the generated archive in `build/distributions`.

### Using a Docker Image
MeGraS is also available as a Docker image. You can pull and run the latest version from Docker Hub using the following command:
````bash
docker run -p 8080:8080 -v ./assets:/assets -it floruosch/megras:latest
````
TODO: move to a more specific Docker image repository.

This will also mount the `assets` directory from your local machine into the container, allowing you to access media files stored there.


## Configuring MeGraS
MeGraS uses an optional configuration file in JSON format.
The file to be used can be passed as a parameter when starting the application.
If no such parameter is provided, MeGraS will look for a `config.json` file in its root directory.
If no such file is found, the default options are used.
The configuration options look as follows:

````json5
{
  "objectStoreBase": "store", //directory to be used as base for the media object store
  "httpPort": 8080,           //port to listen to for HTTP connections
  "backend": "FILE",          //persistent backend to use for graph information
  "fileStore": {              //options to be used for 'FILE' backend
    "filename": "quads.tsv",  //filename to be used to store graph information in
    "compression": false      //store graph information in compressed form
  },
  "cottontailConnection": {   //options to be used for 'COTTONTAIL' backend
    "host": "localhost",
    "port": 1865
  },
  "postgresConnection": {     //options to be used for the 'POSTGRES' backend
    "host": "localhost",
    "port": 5432,
    "database": "megras",
    "user": "megras",
    "password": "megras"
  }
}
````

MeGraS supports several different backend implementations for storing the graph data.
The backend to be used can be selected using the `backend` field in the configuration.
The following graph storage backends are supported:

- `FILE`: keeps all graph triples in memory and periodically dumps everything to a single file.
Suitable for smaller graphs and for testing purposes.
- `COTTONTAIL`: uses the [Cottontail DB](https://github.com/vitrivr/cottontaildb) vector database.
Supports all graph data types, including vector types.
Suitable for medium-sized graphs of several 100k triples up to a few million triples.
- `POSTGRES`: Uses [PostgreSQL](https://www.postgresql.org/) to store the graph.
It also supports vector types and related operations.
Suitable for larger graphs up to several tens of millions of triples.

## FILE Backend
The `FILE` backend is the simplest backend available in MeGraS and requires no additional setup.

## COTTONTAIL Backend
The `COTTONTAIL` backend requires a running instance of Cottontail DB.
TODO: add more details about the Cottontail DB setup and configuration.

## POSTGRES Backend
The `POSTGRES` backend requires a running instance of PostgreSQL.
To set up the database, we recommend that you use the following [docker image](https://docs.timescale.com/self-hosted/latest/install/installation-docker) which contains a preconfigured PostgreSQL instance with the required extensions:

````bash
docker run -d --name timescaledb -p 5432:5432 -e POSTGRES_PASSWORD=password timescale/timescaledb-ha:pg17
````

Then, you can connect to PostgreSQL using a client of your choice (e.g., `psql`) and create the database and user.
If you do not have a PostgreSQL client installed, you can use the following command to connect to the database within the Docker container:
````bash
docker exec -it timescaledb psql -U postgres
````

Now, you can create the database and user with the following commands, setting the username and password to your desired values, according to your configuration:
````sql
CREATE USER megras WITH PASSWORD megras;
CREATE DATABASE megras WITH OWNER megras;
GRANT ALL PRIVILEGES ON DATABASE megras TO megras;
````


# Getting Started
Once MeGraS is up and running, it can be accessed via HTTP on the configured port.
Further documentation is also available [here](GETTING_STARTED.md).