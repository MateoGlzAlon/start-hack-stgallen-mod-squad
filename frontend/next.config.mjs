// The browser only ever talks to this app: /api/* is proxied to the backend (which has no CORS and keeps every key).
// The destination is fixed at build time: BACKEND_URL is a build arg in docker-compose.yml, http://localhost:8080 for local dev.
const backend = (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/+$/, '');

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${backend}/:path*` }];
  },
};

export default nextConfig;
