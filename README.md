# MeGraS
MeGraS, short for **Me**dia**Gra**ph **S**tore, is the data storage and query processing engine of the MediaGraph project
which aims to elevate multimodal data to first-class citizens in Knowledge-Graphs.
MegraS stores and processes multimodal knowledge graphs including its media components as an RDF graph. 

## Installation and Config
MeGraS is written in [Kotlin](https://kotlinlang.org/) and requires a Java runtime environment of version 8 or higher.
For some operations on audio and video data, [ffmpeg](https://ffmpeg.org/) needs to be installed and added to the system path.

### Building MeGraS
MeGraS uses Gradle as a build system. To build the application, simply run `./gradlew distZip` and unpack the generated archive in `build/distributions`.

### Configuring MeGraS
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
  "cottontailConnection": {   //options to be used for 'COTTONTAIL' or 'HYBRID' backend
    "host": "localhost",
    "port": 1865
  },
  "postgresConnection": {     //options to be used for the 'POSTGRES' or 'HYBRID' backend
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
Does **not** support vector types and related operations.
Suitable for larger graphs up to several tens of millions of triples.
- `HYBRID`: Uses both PostgreSQL and Cottontail DB.
The latter is used for vector types and operations, the former for everything else.


## Using the CLI

MeGraS has a built-in command line interface for simple data management tasks.
It enables adding media files as graph nodes and bulk-importing graph triples.
Type `help` to see the available commands and their parameters.

## Using the REST API

MeGraS offers a RESTful API for graph manipulation and querying.
The OpenAPI specification of all available endpoints can be found in the docs directory or by accessing `http://<your_host_and_port>/openapi.json`.
A Swagger UI is available via `http://<your_host_and_port>/swagger-ui`.