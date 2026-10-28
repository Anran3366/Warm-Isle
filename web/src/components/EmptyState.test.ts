import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from './EmptyState.vue'

describe('EmptyState', () => {
  it('renders title', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据' }
    })
    expect(wrapper.find('.empty-title').text()).toBe('暂无数据')
  })

  it('renders description when provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据', desc: '请稍后再试' }
    })
    expect(wrapper.find('.empty-desc').text()).toBe('请稍后再试')
  })

  it('does not render description when not provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据' }
    })
    expect(wrapper.find('.empty-desc').exists()).toBe(false)
  })

  it('renders illustration image when provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据', illustration: '/test.svg' }
    })
    const img = wrapper.find('.empty-img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/test.svg')
    expect(img.attributes('alt')).toBe('暂无数据')
  })

  it('does not render image when illustration not provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据' }
    })
    expect(wrapper.find('.empty-img').exists()).toBe(false)
  })

  it('renders action slot when provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据' },
      slots: { action: '<button>添加</button>' }
    })
    expect(wrapper.find('.empty-action').exists()).toBe(true)
    expect(wrapper.find('button').text()).toBe('添加')
  })

  it('does not render action container when slot not provided', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无数据' }
    })
    expect(wrapper.find('.empty-action').exists()).toBe(false)
  })
})
