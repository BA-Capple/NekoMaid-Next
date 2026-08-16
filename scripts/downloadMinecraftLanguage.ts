import fetchVersion, { exit, get } from './fetchMinecraftVersion'
import { existsSync, mkdirSync, copyFileSync } from 'fs'

if (!existsSync('languages/minecraft')) mkdirSync('languages/minecraft')

// PaperMC 26.2 的 assetIndex 中没有 en_us.json（en_us 内置进 jar），用 en_gb 代替（GUI 词条一致）。
const supportLanguages = ['zh_cn', 'en_gb']
fetchVersion().then(body => get<{ objects: Record<string, { hash: string }> }>(body.assetIndex.url).then(body => supportLanguages.forEach(it => {
  const { hash } = body.objects[`minecraft/lang/${it}.json`]
  require('nugget')(`https://resources.download.minecraft.net/${hash.slice(0, 2)}/${hash}`, { target: `languages/minecraft/${it}.json` }, err => {
    if (err) exit(err)
    if (it === 'en_gb' && !existsSync('languages/minecraft/en_us.json')) {
      copyFileSync('languages/minecraft/en_gb.json', 'languages/minecraft/en_us.json')
      console.log('Generated en_us.json from en_gb.json')
    }
    console.log('Successfully downloaded:', it + '.json')
  })
})))
