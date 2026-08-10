#!/bin/bash

set -e

# allow easier debugging with `docker run -e VERBOSE=yes`
if [[ "$VERBOSE" = "yes" ]]; then
  set -x
fi

# allow easier reset home with `docker run -e RESET_HOME=true`
if [[ "$RESET_HOME" = "true" ]]; then
  echo "Clearing VIVO HOME $VIVO_HOME"
  rm -rf "$VIVO_HOME/*"
fi

# copy home config if not exists
if [ ! -d $VIVO_HOME/config ]; then
  echo "Copying home config directory to $VIVO_HOME/config"
  cp -r /vivo-home/config "$VIVO_HOME/config"
fi

# copy runtime.properties if it does not already exist in target home directory
if [ -f "$VIVO_HOME/config/example.runtime.properties" ]; then
  if [ ! -f "$VIVO_HOME/config/runtime.properties" ]
  then
    echo "Copying example.runtime.properties to $VIVO_HOME/config/runtime.properties"
    cp "$VIVO_HOME/config/example.runtime.properties" "$VIVO_HOME/config/runtime.properties"
  else
    echo "Using existing $VIVO_HOME/config/runtime.properties"
  fi
fi

# Enforce the Solr URL on every start (not just first boot), so a stale
# runtime.properties left pointing at localhost cannot break startup.
if [ -f "$VIVO_HOME/config/runtime.properties" ] && [ -n "$SOLR_URL" ]; then
  echo "Setting runtime.properties vitro.local.solr.url = $SOLR_URL"
  sed -i "s|^[[:space:]]*vitro\.local\.solr\.url[[:space:]]*=.*|vitro.local.solr.url = $SOLR_URL|" "$VIVO_HOME/config/runtime.properties"
fi

# When VIVO_BASE_URL is set (the public address of this instance, e.g.
# http://128.163.202.61:8002), point the default namespace at it so minted
# URIs and links resolve. Only set this before first content is created;
# changing it later orphans existing URIs.
if [ -f "$VIVO_HOME/config/runtime.properties" ] && [ -n "$VIVO_BASE_URL" ]; then
  echo "Setting runtime.properties Vitro.defaultNamespace = $VIVO_BASE_URL/individual/"
  sed -i "s|^[[:space:]]*Vitro\.defaultNamespace[[:space:]]*=.*|Vitro.defaultNamespace = $VIVO_BASE_URL/individual/|" "$VIVO_HOME/config/runtime.properties"
fi

# copy applicationSetup.n3 if it does not already exist in target home directory
if [ -f "$VIVO_HOME/config/example.applicationSetup.n3" ]; then
  if [ ! -f "$VIVO_HOME/config/applicationSetup.n3" ]
  then
    echo "Copying example.applicationSetup.n3 to $VIVO_HOME/config/applicationSetup.n3"
    cp "$VIVO_HOME/config/example.applicationSetup.n3" "$VIVO_HOME/config/applicationSetup.n3"
  else
    echo "Using existing $VIVO_HOME/config/applicationSetup.n3"
  fi
fi

catalina.sh run
