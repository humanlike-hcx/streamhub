import { createApp } from 'vue/dist/vue.esm-bundler.js'
import Hls from 'hls.js'
import './styles.css'

const API_BASE = ''
const WS_BASE = 'ws://localhost:8090'

createApp({
  data() {
    return {
      token: localStorage.getItem('streamhub.token') || '',
      user: JSON.parse(localStorage.getItem('streamhub.user') || 'null'),
      authMode: 'login',
      authForm: {
        username: 'uploadtester',
        password: 'Password123',
        nickname: 'Upload Tester'
      },
      activeView: 'latest',
      keyword: '',
      videos: [],
      page: {
        pageNo: 1,
        pageSize: 12,
        total: 0,
        pages: 0
      },
      uploadForm: {
        title: '',
        description: '',
        file: null
      },
      uploadBusy: false,
      uploadMessage: '',
      loading: false,
      message: '',
      selectedVideo: null,
      playInfo: null,
      hls: null,
      socket: null,
      danmakuText: '',
      danmakuMessages: [],
      danmakuConnected: false
    }
  },
  computed: {
    isLoggedIn() {
      return Boolean(this.token)
    },
    pageSummary() {
      if (!this.page.total) return '暂无内容'
      return `第 ${this.page.pageNo} / ${this.page.pages || 1} 页，共 ${this.page.total} 条`
    }
  },
  mounted() {
    if (this.isLoggedIn) {
      this.loadVideos('latest')
    }
  },
  beforeUnmount() {
    this.closePlayer()
  },
  methods: {
    async request(path, options = {}) {
      const headers = options.headers || {}
      if (this.token) {
        headers.Authorization = `Bearer ${this.token}`
      }
      const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
      const contentType = response.headers.get('content-type') || ''
      const body = contentType.includes('application/json') ? await response.json() : await response.text()
      if (!response.ok || body.code !== 0) {
        throw new Error(body.message || `请求失败：${response.status}`)
      }
      return body.data
    },
    async submitAuth() {
      this.message = ''
      try {
        if (this.authMode === 'register') {
          await this.request('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(this.authForm)
          })
        }
        const data = await this.request('/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: this.authForm.username,
            password: this.authForm.password
          })
        })
        this.token = data.token
        this.user = data.user
        localStorage.setItem('streamhub.token', this.token)
        localStorage.setItem('streamhub.user', JSON.stringify(this.user))
        await this.loadVideos('latest')
      }
      catch (error) {
        this.message = error.message
      }
    },
    logout() {
      this.closePlayer()
      this.token = ''
      this.user = null
      this.videos = []
      localStorage.removeItem('streamhub.token')
      localStorage.removeItem('streamhub.user')
    },
    async loadVideos(view = this.activeView, pageNo = 1) {
      this.loading = true
      this.message = ''
      this.activeView = view
      this.page.pageNo = pageNo
      try {
        let path = `/api/videos?pageNo=${pageNo}&pageSize=${this.page.pageSize}`
        if (view === 'hot') {
          path = `/api/videos/hot?pageNo=${pageNo}&pageSize=${this.page.pageSize}`
        }
        if (view === 'mine') {
          path = `/api/videos/my?pageNo=${pageNo}&pageSize=${this.page.pageSize}`
        }
        if (view === 'search') {
          const keyword = encodeURIComponent(this.keyword.trim())
          path = `/api/videos/search?keyword=${keyword}&pageNo=${pageNo}&pageSize=${this.page.pageSize}`
        }
        const data = await this.request(path)
        this.videos = data.records || []
        this.page = {
          pageNo: data.pageNo,
          pageSize: data.pageSize,
          total: data.total,
          pages: data.pages
        }
      }
      catch (error) {
        this.message = error.message
      }
      finally {
        this.loading = false
      }
    },
    async searchVideos() {
      if (!this.keyword.trim()) {
        await this.loadVideos('latest')
        return
      }
      await this.loadVideos('search')
    },
    selectFile(event) {
      this.uploadForm.file = event.target.files?.[0] || null
    },
    async uploadVideo() {
      if (!this.uploadForm.file) {
        this.uploadMessage = '请选择视频文件'
        return
      }
      this.uploadBusy = true
      this.uploadMessage = ''
      try {
        const formData = new FormData()
        formData.append('title', this.uploadForm.title || this.uploadForm.file.name)
        formData.append('description', this.uploadForm.description || '')
        formData.append('file', this.uploadForm.file)
        await this.request('/api/videos/upload', {
          method: 'POST',
          body: formData
        })
        this.uploadMessage = '上传成功，等待后台转码'
        this.uploadForm = { title: '', description: '', file: null }
        this.$refs.fileInput.value = ''
        await this.loadVideos('mine')
      }
      catch (error) {
        this.uploadMessage = error.message
      }
      finally {
        this.uploadBusy = false
      }
    },
    async openPlayer(video) {
      this.closePlayer()
      this.selectedVideo = video
      this.danmakuMessages = []
      try {
        this.playInfo = await this.request(`/api/videos/${video.id}/play`)
        await this.$nextTick()
        this.attachHls(this.playInfo.hlsMasterUrl)
        this.openDanmaku(video.id)
      }
      catch (error) {
        this.message = error.message
      }
    },
    attachHls(url) {
      const video = this.$refs.player
      const source = `${API_BASE}${url}`
      if (Hls.isSupported()) {
        this.hls = new Hls({
          xhrSetup: (xhr) => {
            if (this.token) xhr.setRequestHeader('Authorization', `Bearer ${this.token}`)
          }
        })
        this.hls.loadSource(source)
        this.hls.attachMedia(video)
      }
      else {
        video.src = source
      }
    },
    openDanmaku(videoId) {
      if (!this.token) return
      const url = `${WS_BASE}/ws/danmaku?videoId=${videoId}&token=${encodeURIComponent(this.token)}`
      this.socket = new WebSocket(url)
      this.socket.onopen = () => {
        this.danmakuConnected = true
        this.socket.send(JSON.stringify({ type: 'PING' }))
      }
      this.socket.onmessage = (event) => {
        const data = this.parseMessage(event.data)
        if (data.type === 'PONG') return
        if (data.content) {
          this.danmakuMessages = [...this.danmakuMessages.slice(-12), data]
        }
      }
      this.socket.onclose = () => {
        this.danmakuConnected = false
      }
    },
    parseMessage(text) {
      try {
        return JSON.parse(text)
      }
      catch {
        return { type: 'DANMAKU', content: text }
      }
    },
    sendDanmaku() {
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN || !this.danmakuText.trim()) return
      this.socket.send(JSON.stringify({ type: 'DANMAKU', content: this.danmakuText.trim() }))
      this.danmakuText = ''
    },
    closePlayer() {
      if (this.hls) {
        this.hls.destroy()
        this.hls = null
      }
      if (this.socket) {
        this.socket.close()
        this.socket = null
      }
      this.selectedVideo = null
      this.playInfo = null
      this.danmakuConnected = false
    },
    formatDuration(seconds) {
      if (!seconds) return '--:--'
      const minutes = Math.floor(seconds / 60)
      const rest = seconds % 60
      return `${minutes}:${String(rest).padStart(2, '0')}`
    }
  },
  template: `
    <main class="app-shell">
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">S</div>
          <div>
            <h1>StreamHub</h1>
            <p>视频上传、播放与弹幕</p>
          </div>
        </div>

        <section v-if="!isLoggedIn" class="panel auth-panel">
          <div class="segmented">
            <button :class="{ active: authMode === 'login' }" @click="authMode = 'login'">登录</button>
            <button :class="{ active: authMode === 'register' }" @click="authMode = 'register'">注册</button>
          </div>
          <label>用户名<input v-model="authForm.username" autocomplete="username" /></label>
          <label>密码<input v-model="authForm.password" type="password" autocomplete="current-password" /></label>
          <label v-if="authMode === 'register'">昵称<input v-model="authForm.nickname" /></label>
          <button class="primary" @click="submitAuth">{{ authMode === 'login' ? '登录' : '注册并登录' }}</button>
        </section>

        <section v-else class="panel">
          <div class="user-row">
            <div class="avatar">{{ user?.nickname?.slice(0, 1) || 'U' }}</div>
            <div>
              <strong>{{ user?.nickname }}</strong>
              <span>@{{ user?.username }}</span>
            </div>
          </div>
          <button class="secondary" @click="logout">退出登录</button>
        </section>

        <section v-if="isLoggedIn" class="panel upload-panel">
          <h2>上传视频</h2>
          <label>标题<input v-model="uploadForm.title" placeholder="默认使用文件名" /></label>
          <label>简介<textarea v-model="uploadForm.description" rows="3" /></label>
          <input ref="fileInput" class="file-input" type="file" accept="video/*" @change="selectFile" />
          <button class="primary" :disabled="uploadBusy" @click="uploadVideo">{{ uploadBusy ? '上传中' : '上传' }}</button>
          <p class="hint">{{ uploadMessage }}</p>
        </section>
      </aside>

      <section class="content">
        <header class="topbar">
          <div class="searchbar">
            <input v-model="keyword" placeholder="搜索标题或简介" @keydown.enter="searchVideos" />
            <button @click="searchVideos">搜索</button>
          </div>
          <div class="tabs">
            <button :class="{ active: activeView === 'latest' }" @click="loadVideos('latest')">最新</button>
            <button :class="{ active: activeView === 'hot' }" @click="loadVideos('hot')">热门</button>
            <button :class="{ active: activeView === 'mine' }" @click="loadVideos('mine')">我的</button>
          </div>
        </header>

        <p v-if="message" class="alert">{{ message }}</p>

        <div v-if="!isLoggedIn" class="empty-state">
          <h2>登录后体验 StreamHub</h2>
          <p>可以上传视频、查看转码结果、搜索内容并在播放页发送弹幕。</p>
        </div>

        <template v-else>
          <div class="meta-line">
            <span>{{ loading ? '加载中...' : pageSummary }}</span>
            <div class="pager">
              <button :disabled="page.pageNo <= 1" @click="loadVideos(activeView, page.pageNo - 1)">上一页</button>
              <button :disabled="page.pageNo >= page.pages" @click="loadVideos(activeView, page.pageNo + 1)">下一页</button>
            </div>
          </div>

          <div class="video-grid">
            <article v-for="video in videos" :key="video.id" class="video-card">
              <div class="cover" @click="video.playable && openPlayer(video)">
                <img v-if="video.coverUrl" :src="video.coverUrl" :alt="video.title" />
                <div v-else class="cover-fallback">{{ video.status }}</div>
                <span class="duration">{{ formatDuration(video.duration) }}</span>
              </div>
              <div class="video-info">
                <h3>{{ video.title }}</h3>
                <p>{{ video.description || '暂无简介' }}</p>
                <div class="stats">
                  <span>{{ video.status }}</span>
                  <span>{{ video.playCount || 0 }} 播放</span>
                  <span>{{ video.likeCount || 0 }} 赞</span>
                </div>
              </div>
            </article>
          </div>
        </template>
      </section>

      <div v-if="selectedVideo" class="player-layer">
        <section class="player-panel">
          <button class="close" @click="closePlayer">×</button>
          <div class="video-stage">
            <video ref="player" controls autoplay playsinline></video>
            <div class="danmaku-overlay">
              <p v-for="(item, index) in danmakuMessages" :key="index" :style="{ top: (index % 8) * 12 + 6 + '%' }">
                {{ item.content }}
              </p>
            </div>
          </div>
          <div class="player-footer">
            <div>
              <h2>{{ selectedVideo.title }}</h2>
              <p>{{ danmakuConnected ? '弹幕已连接' : '弹幕未连接' }}</p>
            </div>
            <div class="danmaku-form">
              <input v-model="danmakuText" placeholder="发送弹幕" @keydown.enter="sendDanmaku" />
              <button @click="sendDanmaku">发送</button>
            </div>
          </div>
        </section>
      </div>
    </main>
  `
}).mount('#app')
