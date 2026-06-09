#!/bin/bash

cd ~/serveurWeb/bin
nohup java MainServeur > ../run/server.log 2>&1 &
echo "Serveur démarré"