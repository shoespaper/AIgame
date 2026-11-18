import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import CreateView from './views/CreateView.vue'
import PlayView from './views/PlayView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/create', name: 'create', component: CreateView },
    { path: '/play/:gameId', name: 'play', component: PlayView },
  ],
})
