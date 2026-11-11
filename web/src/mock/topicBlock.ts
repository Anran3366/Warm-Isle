import type { TopicBlockConfig } from '@/api/types'

/** 话题屏蔽的初始配置，normal / extreme 两套 Mock 共用 */
export function defaultTopicBlock(): TopicBlockConfig {
  return {
    enabled: true,
    action: 'deflect',
    replyTemplate: '这个话题我不太想聊欸，我们换个别的吧～',
    customTopics: ['前任', '体重'],
    presets: [
      {
        id: 'porn',
        label: '色情低俗',
        desc: '性行为、裸露、色情资源、擦边挑逗',
        enabled: true,
        keywords: ['色情', '黄片', '裸照', '裸聊', '约炮', '做爱', '性行为', '自慰', '成人视频', '福利姬', '擦边']
      },
      {
        id: 'politics',
        label: '国家政治',
        desc: '党政、领导人、政策路线、国际冲突等立场讨论',
        enabled: true,
        keywords: ['政治', '共产党', '国家主席', '领导人', '总统', '选举', '换届', '政变', '游行', '示威', '台独', '港独', '六四', '法轮功']
      },
      {
        id: 'violence',
        label: '暴力血腥',
        desc: '打斗、虐待、血腥画面、恐怖袭击',
        enabled: true,
        keywords: ['杀人', '血腥', '虐待', '分尸', '恐怖袭击', '爆炸', '枪杀']
      },
      {
        id: 'illegal',
        label: '违法犯罪',
        desc: '毒品、赌博、诈骗、枪支、走私等',
        enabled: true,
        keywords: ['毒品', '冰毒', '大麻', '赌博', '洗钱', '诈骗', '走私', '枪支', '代开发票', '刷单']
      },
      {
        id: 'spam',
        label: '广告引流',
        desc: '拉群、推广、返利、加微信等营销内容',
        enabled: true,
        keywords: ['加微信', '加V', '拉群', '代理', '返利', '兼职', '推广', '引流', '优惠券', '扫码']
      }
    ]
  }
}

/** Mock 内做一次深拷贝，避免页面直接改到 Mock 的源对象 */
export function cloneTopicBlock(config: TopicBlockConfig): TopicBlockConfig {
  return JSON.parse(JSON.stringify(config)) as TopicBlockConfig
}
