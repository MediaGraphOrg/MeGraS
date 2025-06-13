# Getting Started with MeGraS

Welcome to MeGraS! This guide will help you get started with using the REST API and CLI for graph manipulation and querying.

---

## Interacting and Accessing the Graph

### Using the CLI
MeGraS has a built-in command line interface for simple data management tasks.
It enables adding media files as graph nodes and bulk-importing graph triples.
Type `help` to see the available commands and their parameters.

#### Example CLI Commands
To add a media file to the graph, use the `add` command:
````bash
add -f example.jpeg
````

To add the contents of a folder, use the `add` command with the `-r` flag:
````bash
add -r -f /examples
````

To load a TSV file containing graph triples, use the `import` command:
````bash
import -f triples.tsv
````
It provides the following optional flags:
- `-b` to specify the batch size for importing triples (default is 100)
- `-s` to skip the specified number of lines in the file (for TSV files with headers)

### Using the REST API
MeGraS offers a RESTful API for graph manipulation and querying.
The OpenAPI specification of all available endpoints can be found in the docs directory or [here](/openapi.json).

Important Endpoints
- **Swagger UI**: [/swagger-ui](/swagger-ui)
- **List of Predicates**: [/predicateinformation](/predicateinformation)
- **SPARQL UI**: [/sparqlui](/sparqlui)
  - Set the SPARQL Endpoint to `http://<host>:<port>/query/sparql`
  - SPARQL examples are available from `https://github.com/floruo/MeGraS-SPARQL-Queries`
- **Interacting with the graph**:
	- **Uploading files**: [/fileupload](/fileupload)
	- **Adding triples**: [/addtriples](/addtriples)


## Additional Services
Certain services are provided by a Python server running on the same host as MeGraS.
This currently includes:
- **CLIP Embedder**: A service for generating embeddings from text and images.
- **OCR**: A service for Optical Character Recognition (OCR) to extract text from images.

To start these services, run the following command, assuming you are in the project's root directory:
````bash
cd python_grpc_server
pip install -r requirements.txt
python server.py
````
It is advised to run the server in a virtual environment to avoid conflicts with other Python packages.