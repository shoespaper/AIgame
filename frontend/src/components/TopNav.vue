<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

interface NavItem { label: string; path: string; phase?: string }

/** 顶部主导航项；带 phase 的项通过 query 区分同一路径下的不同视图。 */
const items: NavItem[] = [
  { label: '总览', path: '/' },
  { label: '创建新冒险', path: '/game', phase: 'selection' },
  { label: '世界书', path: '/worldbooks' },
  { label: '存档', path: '/game', phase: 'saves' },
  { label: '设置', path: '/settings' },
]

/** 判断导航项是否对应当前页面。
 * @param item 导航项
 */
function isActive(item: NavItem): boolean {
  if (item.phase !== undefined) {
    const phase = route.query.phase ?? 'selection'
    return route.path === item.path && phase === item.phase
  }
  return route.path === item.path
}

/** 跳转到导航项。
 * @param item 导航项
 */
function go(item: NavItem) {
  if (item.phase !== undefined) router.push({ path: item.path, query: item.phase === 'saves' ? { phase: 'saves' } : {} })
  else router.push(item.path)
}
</script>

<template>
  <header class="onboarding-nav top-nav">
    <button class="onboarding-brand" type="button" aria-label="返回首页" @click="router.push('/')">AIgame</button>
    <nav aria-label="主导航">
      <template v-for="item in items" :key="item.label">
        <span v-if="isActive(item)" class="active">{{ item.label }}</span>
        <button v-else type="button" @click="go(item)">{{ item.label }}</button>
      </template>
    </nav>
  </header>
</template>
