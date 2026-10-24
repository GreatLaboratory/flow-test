import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 시에만 API를 Spring(8080)으로 프록시. 운영은 Spring이 빌드 결과를 같은 출처로 서빙한다(CORS 없음).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
