#!/bin/bash
# LocalCloud — Stop and remove container
docker stop localcloud 2>/dev/null && docker rm localcloud 2>/dev/null
echo "LocalCloud stopped."
