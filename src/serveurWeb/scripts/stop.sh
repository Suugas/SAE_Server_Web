#!/bin/bash

PIDFILE=~/serveurWeb/run/myweb.pid

if [ -f "$PIDFILE" ]; then
    PID=$(cat $PIDFILE)
    kill $PID
    rm $PIDFILE
    echo "Serveur arrêté"
else
    echo "PID introuvable"
fi