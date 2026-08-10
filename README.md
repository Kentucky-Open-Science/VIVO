# VIVO: Connect, Share, Discover

[![Build](https://github.com/vivo-project/VIVO/workflows/Build/badge.svg)](https://github.com/vivo-project/VIVO/actions?query=workflow%3ABuild) [![Deploy](https://github.com/vivo-project/VIVO/workflows/Deploy/badge.svg)](https://github.com/vivo-project/VIVO/actions?query=workflow%3ADeploy) [![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.2639714.svg)](https://doi.org/10.5281/zenodo.2639713)

VIVO is an open source semantic web tool for research discovery -- finding people and the research they do.

## Fork additions (Kentucky Open Science)

This fork adds capabilities inspired by the [UK Research Knowledge Graph](https://github.com/Kentucky-Open-Science) project:

| Capability | Route | Notes |
| ---------- | ----- | ----- |
| Statistics dashboard | `/dashboard` | Live entity counts, recent publications, top researchers and organizations |
| Interactive graph explorer | `/graphExplorer` | D3 force-directed view of the knowledge graph; search, click-to-expand neighborhoods |
| Ask VIVO (AI search) | `/askVivo` | Natural-language questions translated to SPARQL by an OpenAI-compatible LLM, executed read-only, results summarized. Configure `llm.baseUrl`, `llm.apiKey`, `llm.model` in `runtime.properties` |
| Scholarly data ingest | `/dataIngest` | Built-in PubMed (NCBI eUtils) and NIH RePORTER harvesting into VIVO RDF with idempotent URIs; linked from Site Admin &rarr; Advanced Data Tools |

Links to the three public pages are in the site footer (wilma theme), the dashboard has action buttons, and an "Ask VIVO" button sits beside the site search box. The ingest tool requires the Advanced Data Tools permission (log in as an admin).

### Deploying with Docker Compose

Set in `.env`:

- `VIVO_HOST_PORT` — public port (dev server: `8002`)
- `VIVO_BASE_URL` — public address (e.g. `http://128.163.202.61:8002`); `start.sh` templates `Vitro.defaultNamespace` from it on container start so minted URIs resolve. Set it **before** first content is created — changing it later orphans existing URIs.

The container now enforces `vitro.local.solr.url` (pointing at the `vivo-solr` service) on **every** start, so a stale `runtime.properties` pointing at `localhost:8983` can no longer break startup with "Could not set up the Solr search engine".

An automatic PubMed harvest on startup can be enabled with `ingest.pubmed.onStartup = true` in `runtime.properties` (see `example.runtime.properties` for the related keys).

Known limitations of the fork additions:

- PubMed authors are created as `vcard:Individual` name stubs (VIVO's convention for unclaimed authors), not `foaf:Person` profiles — so PubMed-only data does not feed "Top Researchers" or the co-authorship overview until authors are claimed or profiles created. NIH RePORTER PIs *do* become `foaf:Person` individuals (keyed by RePORTER profile id).
- The Ask VIVO endpoint enforces read-only SELECT queries with a row cap, but an adversarial question could still produce an expensive aggregate query; the rate limits (4/min per session, 10/min global) bound the damage.
- Dashboard responses are cached for 5 minutes globally (first requester's locale wins; a transient store failure can cache zeros for one cycle).
- A running harvest is a daemon thread: stopping Tomcat mid-harvest simply abandons the batch in progress (idempotent URIs make re-running safe).

VIVO supports editing, searching, browsing and visualizing research activity in order to discover people, programs, 
facilities, funding, scholarly works and events. VIVO's search returns results faceted by type for rapid retrieval of 
desired information across disciplines.

## Resources

### VIVO Project web site
[https://vivo.lyrasis.org/](https://vivo.lyrasis.org/)

### VIVO Project Wiki
https://wiki.lyrasis.org/display/VIVO/

### Installation Instructions

Installation instructions for all releases can be found at this location on the wiki:  
https://wiki.lyrasis.org/display/VIVODOC/All+Documentation

When you select the wiki pages for technical documentation for the release you would like to install at https://wiki.lyrasis.org/display/VIVODOC/All+Documentation, please open the Installing VIVO section and follow the instructions. 

### Docker

VIVO docker container is available at [vivoweb/vivo](https://hub.docker.com/repository/docker/vivoweb/vivo) with accompanying [vivoweb/vivo-solr](https://hub.docker.com/repository/docker/vivoweb/vivo-solr). These can be used independently or with docker-compose.

### Docker Compose

Docker Compose variable substitution:

.env defaults
```
SOLR_RESET_CORE=false
SOLR_VERBOSE=no

SOLR_HOST_PORT=8983
SOLR_CONTAINER_PORT=8983

SOLR_CORES=./vivo-cores

VIVO_RESET_HOME=false
VIVO_VERBOSE=no

VIVO_TDB_FILE_MODE=direct

VIVO_HOST_VIVO_HOME=./vivo-home
VIVO_CONTAINER_VIVO_HOME=/usr/local/vivo/home

VIVO_HOST_PORT=8080
VIVO_CONTAINER_PORT=8080
```

- `SOLR_RESET_CORE`: Convenience to reset VIVO Solr core when starting container. **Caution**, will require complete reindex.
- `SOLR_VERBOSE`: Increase log verbosity.
- `SOLR_HOST_PORT`: Host port binding for solr service port mapping.
- `SOLR_CONTAINER_PORT`: Container port binding for solr service port mapping.
- `SOLR_CORES`: Solr cores data directories on your host machine which will mount to volume in docker container. Set this environment variable to persist your Solr data on your host machine.

- `VIVO_RESET_HOME`: Convenience to reset VIVO home when starting container. **Caution**, will delete local configuration, content, and configuration model.
- `VIVO_VERBOSE`: Increase log verbosity.
- `VIVO_TDB_FILE_MODE`: TDB file mode. See https://jena.apache.org/documentation/tdb/configuration.html#file-access-mode.
- `VIVO_HOST_VIVO_HOME`: VIVO home directory on your host machine which will mount to volume in docker container. Set this environment variable to persist your VIVO data on your host machine.
- `VIVO_CONTAINER_VIVO_HOME`: VIVO home directory within the container.
- `VIVO_HOST_PORT`: Host port binding for VIVO Tomcat service port mapping.
- `VIVO_CONTAINER_PORT`: Container port binding for VIVO Tomcat service port mapping.

Before building VIVO, you will also need to clone (and switch to the same branch, if other than main) of [Vitro](https://github.com/vivo-project/Vitro). The Vitro project must be cloned to a sibling directory next to VIVO so that it can be found during the build. 

Build and start VIVO.

1. In VIVO (with Vitro cloned alongside it), run:
```
mvn clean package -s installer/docker-example-settings.xml
docker-compose up
```

### Docker Image

To build and run local Docker image.

```
docker build -t vivoweb/vivo:development .
docker run -p 8080:8080 vivoweb/vivo:development
```

## Community
There are several ways to contact and join the VIVO community. All of them are listed at [https://vivoweb.org/contact/](https://vivoweb.org/contact/).

## Contributing Code
If you would like to contribute code to the VIVO project, please read instructions at [this page](https://github.com/vivo-project/VIVO/wiki/Development-Processes#process-for-suggesting-contribution).  Contributors welcome!

## Citing VIVO
If you are using VIVO in your publications or projects, please cite the software paper in the Journal of Open Source Software:

* Conlon et al., (2019). VIVO: a system for research discovery. Journal of Open Source Software, 4(39), 1182, https://doi.org/10.21105/joss.01182

### BibTeX
```tex
@article{Conlon2019,
  doi = {10.21105/joss.01182},
  url = {https://doi.org/10.21105/joss.01182},
  year = {2019},
  publisher = {The Open Journal},
  volume = {4},
  number = {39},
  pages = {1182},
  author = {Michael Conlon and Andrew Woods and Graham Triggs and Ralph O'Flinn and Muhammad Javed and Jim Blake and Benjamin Gross and Qazi Asim Ijaz Ahmad and Sabih Ali and Martin Barber and Don Elsborg and Kitio Fofack and Christian Hauschke and Violeta Ilik and Huda Khan and Ted Lawless and Jacob Levernier and Brian Lowe and Jose Martin and Steve McKay and Simon Porter and Tatiana Walther and Marijane White and Stefan Wolff and Rebecca Younes},
  title = {{VIVO}: a system for research discovery},
  journal = {Journal of Open Source Software}
}
