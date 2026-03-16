#!/bin/bash

# StationlyUI Automated Deployment Script
# Deploys to Oracle Cloud: stationly.co.uk

# Errors & Debugging
set -e

# Configuration
SERVER_IP="141.147.91.64"
SERVER_USER="ubuntu"
SSH_KEY="/Users/nikhilkumar/workspace/Projects/ssh_oracle_staionly_backend.key"
REMOTE_DIR="/var/www/stationly-web"
LOCAL_DIST="web/build/dist"
NGINX_CONF_NAME="stationly-web"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}🚀 Starting Stationly Deployment to ${SERVER_IP}...${NC}"

# 1. Pre-flight Checks
if [ ! -f "$SSH_KEY" ]; then
    echo -e "${RED}❌ SSH Key not found at $SSH_KEY${NC}"
    exit 1
fi
chmod 600 "$SSH_KEY"

# 2. Build Project
echo -e "${YELLOW}📦 Building project...${NC}"

# Clean previous build
rm -rf "$LOCAL_DIST"

# Install dependencies if node_modules missing
if [ ! -d "web/node_modules" ]; then
    echo "Installing frontend dependencies..."
    (cd web && npm install)
fi

# Build with Vite
echo "Running Vite build..."
(cd web && npm run build)

if [ ! -d "$LOCAL_DIST" ]; then
    echo -e "${RED}❌ Build failed. Directory $LOCAL_DIST not found.${NC}"
    exit 1
fi
# Note: Vite already handles CSS bundling and minification, so we don't need manual bundling.
# Vite outputs to assets/*.css and assets/*.js and authentic index.html automatically.

# Remove potential garbage if it leaked into dist (optional safety)
rm -rf "$LOCAL_DIST/node_modules" "$LOCAL_DIST/manifest.json" "$LOCAL_DIST/package-lock.json"

# 3. Create Archive
echo -e "${YELLOW}📦 Compressing assets...${NC}"
TAR_NAME="stationly-deploy-$(date +%s).tar.gz"
tar -czf "$TAR_NAME" -C "$LOCAL_DIST" .

# 4. Generate Nginx Config Locally
echo -e "${YELLOW}📝 Generating Nginx configuration...${NC}"
cat > nginx_deploy.conf << 'EOF'
server {
    listen 80;
    server_name stationly.co.uk www.stationly.co.uk;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name stationly.co.uk www.stationly.co.uk;

    root /var/www/stationly-web;
    index index.html;

    # SSL - assume existing certificates or updated by certbot
    ssl_certificate /etc/letsencrypt/live/stationly.co.uk/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/stationly.co.uk/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # Gzip Compression
    gzip on;
    gzip_types text/plain text/css text/javascript application/javascript application/json image/svg+xml;
    gzip_min_length 1000;

    # Caching (Disabled for dev/updates)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
        expires -1;
        add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0";
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0";
    }
}
EOF

# 5. Upload Files
echo -e "${YELLOW}📤 Uploading to server...${NC}"
scp -i "$SSH_KEY" -o StrictHostKeyChecking=no "$TAR_NAME" "$SERVER_USER@$SERVER_IP:/tmp/$TAR_NAME"
scp -i "$SSH_KEY" -o StrictHostKeyChecking=no nginx_deploy.conf "$SERVER_USER@$SERVER_IP:/tmp/$NGINX_CONF_NAME.conf"

# 6. Remote Execution
echo -e "${YELLOW}🛠️  Configuring remote server...${NC}"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$SERVER_USER@$SERVER_IP" "bash -s" << REMOTE_SCRIPT
    set -e
    
    # 1. Install Nginx if missing
    if ! command -v nginx &> /dev/null; then
        echo "Installing Nginx..."
        sudo apt-get update
        sudo apt-get install -y nginx
    fi

    # 2. Setup Web Directory
    echo "Deploying files..."
    sudo mkdir -p $REMOTE_DIR
    sudo tar -xzf /tmp/$TAR_NAME -C $REMOTE_DIR
    
    # Set permissions
    sudo chown -R www-data:www-data $REMOTE_DIR
    sudo chmod -R 755 $REMOTE_DIR

    # 3. Setup Nginx Config
    echo "Updating Nginx config..."
    sudo mv /tmp/$NGINX_CONF_NAME.conf /etc/nginx/sites-available/$NGINX_CONF_NAME
    
    # Enable site if not enabled
    if [ ! -f /etc/nginx/sites-enabled/$NGINX_CONF_NAME ]; then
        sudo ln -s /etc/nginx/sites-available/$NGINX_CONF_NAME /etc/nginx/sites-enabled/
        sudo rm -f /etc/nginx/sites-enabled/default
    fi

    # 4. Check SSL Keys (Simple Check)
    if [ ! -f /etc/letsencrypt/live/stationly.co.uk/fullchain.pem ]; then
        echo "⚠️  SSL Certificates missing! You may need to run Certbot manually."
        echo "    sudo certbot --nginx -d stationly.co.uk -d www.stationly.co.uk"
        
        # Fallback to HTTP for now to avoid crash if regular nginx start fails?
        # No, better to warn. The user can run certbot once.
    else
        echo "✓ SSL Certificates found."
    fi

    # 5. Reload Nginx
    sudo nginx -t && sudo systemctl reload nginx
    echo "✓ Nginx reloaded"

    # Cleanup
    rm /tmp/$TAR_NAME
REMOTE_SCRIPT

# 7. Cleanup Local
rm "$TAR_NAME" nginx_deploy.conf

echo -e "${GREEN}✅ Deployment Complete!${NC}"
echo -e "Visit: https://stationly.co.uk"
