import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      // Realtime voice WebSocket (ws/wss upgrade)
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
})
