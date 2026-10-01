/*
 * Generates missing item icons from the official client model graph. Mojang textures themselves
 * stay ignored; this script and minecraftIcons.json are the reproducible, redistributable build
 * recipe. It intentionally uses a small software renderer so CI needs neither OpenGL nor a native
 * canvas module.
 */
const fs = require('fs')
const path = require('path')
const yauzl = require('yauzl')
const { PNG } = require('pngjs')

const jar = process.argv[2] || 'client.jar'
const outputDir = process.argv[3] || 'icons/minecraft'
const mappingFile = process.argv[4] || 'minecraftIcons.json'
const SIZE = 40

const openZip = filename => new Promise((resolve, reject) => yauzl.open(filename, { lazyEntries: true }, (error, zip) => error ? reject(error) : resolve(zip)))
const readEntry = (zip, entry) => new Promise((resolve, reject) => zip.openReadStream(entry, (error, stream) => {
  if (error) return reject(error)
  const chunks = []
  stream.on('data', chunk => chunks.push(chunk))
  stream.on('error', reject)
  stream.on('end', () => resolve(Buffer.concat(chunks)))
}))

const loadAssets = async () => {
  const zip = await openZip(jar)
  const assets = new Map()
  await new Promise((resolve, reject) => {
    zip.on('entry', async entry => {
      try {
        const name = entry.fileName
        const wanted = /^assets\/minecraft\/(items|models)\/.*\.json$/.test(name) ||
          /^assets\/minecraft\/textures\/(item|block)\/.*\.png$/.test(name)
        if (wanted) assets.set(name, await readEntry(zip, entry))
        zip.readEntry()
      } catch (error) { reject(error) }
    })
    zip.on('end', resolve)
    zip.on('error', reject)
    zip.readEntry()
  })
  zip.close()
  return assets
}

const stripNamespace = value => value.replace(/^minecraft:/, '')
const jsonOf = (assets, name) => {
  const data = assets.get(name)
  return data ? JSON.parse(data.toString('utf8')) : null
}

const modelReferences = node => {
  if (!node || typeof node !== 'object') return []
  const type = stripNamespace(node.type || '')
  if (type === 'model') return node.model ? [node.model] : []
  if (type === 'special') return node.base ? [node.base] : []
  if (type === 'composite') return (node.models || []).flatMap(modelReferences)
  if (type === 'condition') return [...modelReferences(node.on_false), ...modelReferences(node.on_true)]
  if (type === 'select') return [...modelReferences(node.fallback), ...(node.cases || []).flatMap(it => modelReferences(it.model))]
  if (type === 'range_dispatch') return [...modelReferences(node.fallback), ...(node.entries || []).flatMap(it => modelReferences(it.model))]
  return []
}

const resolveModel = (assets, reference, seen = new Set()) => {
  const ref = stripNamespace(reference)
  if (seen.has(ref)) return null
  seen.add(ref)
  const own = jsonOf(assets, `assets/minecraft/models/${ref}.json`)
  if (!own) return null
  let parent = null
  if (own.parent && !own.parent.startsWith('builtin/')) parent = resolveModel(assets, own.parent, seen)
  return {
    textures: { ...(parent?.textures || {}), ...(own.textures || {}) },
    elements: own.elements || parent?.elements || [],
    ambientocclusion: own.ambientocclusion ?? parent?.ambientocclusion
  }
}

const textureName = (model, value, seen = new Set()) => {
  if (!value) return null
  if (!value.startsWith('#')) return stripNamespace(value)
  const key = value.slice(1)
  if (seen.has(key)) return null
  seen.add(key)
  return textureName(model, model.textures[key], seen)
}

const texturePng = (assets, name) => {
  if (!name) return null
  const data = assets.get(`assets/minecraft/textures/${name}.png`)
  if (!data) return null
  try { return PNG.sync.read(data) } catch { return null }
}

const rotatePoint = (point, rotation) => {
  if (!rotation || !rotation.axis || !rotation.angle) return point
  const origin = rotation.origin || [8, 8, 8]
  const p = point.map((value, index) => value - origin[index])
  const angle = rotation.angle * Math.PI / 180
  const c = Math.cos(angle); const s = Math.sin(angle)
  let [x, y, z] = p
  if (rotation.axis === 'x') [y, z] = [y * c - z * s, y * s + z * c]
  else if (rotation.axis === 'y') [x, z] = [x * c + z * s, -x * s + z * c]
  else if (rotation.axis === 'z') [x, y] = [x * c - y * s, x * s + y * c]
  return [x + origin[0], y + origin[1], z + origin[2]]
}

const faceVertices = (from, to, direction) => {
  const [x0, y0, z0] = from; const [x1, y1, z1] = to
  const faces = {
    north: [[x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z0]],
    south: [[x0, y0, z1], [x0, y1, z1], [x1, y1, z1], [x1, y0, z1]],
    west: [[x0, y0, z0], [x0, y1, z0], [x0, y1, z1], [x0, y0, z1]],
    east: [[x1, y0, z1], [x1, y1, z1], [x1, y1, z0], [x1, y0, z0]],
    up: [[x0, y1, z1], [x0, y1, z0], [x1, y1, z0], [x1, y1, z1]],
    down: [[x0, y0, z0], [x0, y0, z1], [x1, y0, z1], [x1, y0, z0]]
  }
  return faces[direction]
}

const project = point => {
  let [x, y, z] = point.map(value => value - 8)
  const yaw = Math.PI / 4; const pitch = Math.PI / 6
  const rx = x * Math.cos(yaw) - z * Math.sin(yaw)
  let rz = x * Math.sin(yaw) + z * Math.cos(yaw)
  const ry = y * Math.cos(pitch) - rz * Math.sin(pitch)
  rz = y * Math.sin(pitch) + rz * Math.cos(pitch)
  return { x: rx, y: -ry, z: rz }
}

const blend = (image, x, y, source, shade) => {
  if (x < 0 || y < 0 || x >= image.width || y >= image.height || source[3] === 0) return
  const index = (y * image.width + x) * 4
  const alpha = source[3] / 255
  const inverse = 1 - alpha
  image.data[index] = Math.round(source[0] * shade * alpha + image.data[index] * inverse)
  image.data[index + 1] = Math.round(source[1] * shade * alpha + image.data[index + 1] * inverse)
  image.data[index + 2] = Math.round(source[2] * shade * alpha + image.data[index + 2] * inverse)
  image.data[index + 3] = Math.round((alpha + image.data[index + 3] / 255 * inverse) * 255)
}

const drawTriangle = (output, texture, vertices, uvs, shade) => {
  const [a, b, c] = vertices; const [ta, tb, tc] = uvs
  const minX = Math.max(0, Math.floor(Math.min(a.x, b.x, c.x)))
  const maxX = Math.min(output.width - 1, Math.ceil(Math.max(a.x, b.x, c.x)))
  const minY = Math.max(0, Math.floor(Math.min(a.y, b.y, c.y)))
  const maxY = Math.min(output.height - 1, Math.ceil(Math.max(a.y, b.y, c.y)))
  const denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
  if (Math.abs(denominator) < 1e-6) return
  const frame = Math.min(texture.width, texture.height)
  for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
    const px = x + 0.5; const py = y + 0.5
    const w1 = ((b.y - c.y) * (px - c.x) + (c.x - b.x) * (py - c.y)) / denominator
    const w2 = ((c.y - a.y) * (px - c.x) + (a.x - c.x) * (py - c.y)) / denominator
    const w3 = 1 - w1 - w2
    if (w1 < -0.001 || w2 < -0.001 || w3 < -0.001) continue
    const u = Math.max(0, Math.min(frame - 1, Math.floor((ta[0] * w1 + tb[0] * w2 + tc[0] * w3) / 16 * frame)))
    const v = Math.max(0, Math.min(frame - 1, Math.floor((ta[1] * w1 + tb[1] * w2 + tc[1] * w3) / 16 * frame)))
    const ti = (v * texture.width + u) * 4
    blend(output, x, y, texture.data.subarray(ti, ti + 4), shade)
  }
}

const renderModel = (assets, model) => {
  const quads = []
  const shades = { up: 1, down: 0.55, north: 0.78, south: 0.78, west: 0.65, east: 0.65 }
  for (const element of model.elements || []) {
    for (const [direction, face] of Object.entries(element.faces || {})) {
      const texture = texturePng(assets, textureName(model, face.texture))
      const points = faceVertices(element.from || [0, 0, 0], element.to || [16, 16, 16], direction)
      if (!texture || !points) continue
      const projected = points.map(point => project(rotatePoint(point, element.rotation)))
      let uv = face.uv || [0, 0, 16, 16]
      let uvs = [[uv[0], uv[3]], [uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]]]
      const turns = (((face.rotation || 0) / 90) % 4 + 4) % 4
      if (turns) uvs = uvs.map((_, index) => uvs[(index + turns) % 4])
      quads.push({ projected, uvs, texture, shade: shades[direction] || 0.8,
        depth: projected.reduce((sum, point) => sum + point.z, 0) / 4 })
    }
  }
  if (!quads.length) return null
  const all = quads.flatMap(quad => quad.projected)
  const minX = Math.min(...all.map(point => point.x)); const maxX = Math.max(...all.map(point => point.x))
  const minY = Math.min(...all.map(point => point.y)); const maxY = Math.max(...all.map(point => point.y))
  const scale = Math.min(34 / Math.max(1, maxX - minX), 34 / Math.max(1, maxY - minY))
  const centerX = (minX + maxX) / 2; const centerY = (minY + maxY) / 2
  for (const quad of quads) quad.projected = quad.projected.map(point => ({
    ...point, x: (point.x - centerX) * scale + SIZE / 2, y: (point.y - centerY) * scale + SIZE / 2
  }))
  const output = new PNG({ width: SIZE, height: SIZE })
  quads.sort((a, b) => a.depth - b.depth)
  for (const quad of quads) {
    drawTriangle(output, quad.texture, [quad.projected[0], quad.projected[1], quad.projected[2]], [quad.uvs[0], quad.uvs[1], quad.uvs[2]], quad.shade)
    drawTriangle(output, quad.texture, [quad.projected[0], quad.projected[2], quad.projected[3]], [quad.uvs[0], quad.uvs[2], quad.uvs[3]], quad.shade)
  }
  return PNG.sync.write(output)
}

const flatIcon = texture => {
  const output = new PNG({ width: SIZE, height: SIZE })
  const frame = Math.min(texture.width, texture.height)
  const draw = 32
  for (let y = 0; y < draw; y++) for (let x = 0; x < draw; x++) {
    const sx = Math.min(frame - 1, Math.floor(x / draw * frame)); const sy = Math.min(frame - 1, Math.floor(y / draw * frame))
    const source = (sy * texture.width + sx) * 4; const target = ((y + 4) * SIZE + x + 4) * 4
    texture.data.copy(output.data, target, source, source + 4)
  }
  return PNG.sync.write(output)
}

const representativeTexture = (assets, model) => {
  const priorities = ['layer0', 'particle', 'all', 'top', 'side', 'end', 'bottom']
  for (const key of [...priorities, ...Object.keys(model.textures || {})]) {
    const texture = texturePng(assets, textureName(model, model.textures[key]))
    if (texture) return texture
  }
  return null
}

const listPngBasenames = directory => {
  const result = new Set()
  const visit = current => {
    if (!fs.existsSync(current)) return
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) visit(target)
      else if (entry.name.endsWith('.png')) result.add(entry.name.slice(0, -4))
    }
  }
  visit(directory)
  return result
}

const main = async () => {
  fs.mkdirSync(outputDir, { recursive: true })
  const assets = await loadAssets()
  const itemEntries = [...assets.keys()].filter(name => /^assets\/minecraft\/items\/[^/]+\.json$/.test(name))
  let generated = 0; const unresolved = []
  for (const entry of itemEntries) {
    const id = path.basename(entry, '.json')
    const output = path.join(outputDir, `${id}.png`)
    if (fs.existsSync(output)) continue
    const definition = jsonOf(assets, entry)
    let icon = null
    for (const reference of modelReferences(definition.model)) {
      const model = resolveModel(assets, reference)
      if (!model) continue
      icon = renderModel(assets, model)
      if (!icon) {
        const texture = representativeTexture(assets, model)
        if (texture) icon = flatIcon(texture)
      }
      if (icon) break
    }
    if (icon) {
      fs.writeFileSync(output, icon)
      generated++
    } else unresolved.push(id)
  }
  const map = {}
  for (const name of [...listPngBasenames(outputDir)].sort()) map[name] = 0
  fs.writeFileSync(mappingFile, JSON.stringify(map))
  console.log(`Minecraft item models: ${itemEntries.length}; generated: ${generated}; mapped icons: ${Object.keys(map).length}; unresolved: ${unresolved.length}`)
  if (unresolved.length) console.log(`Unresolved item models: ${unresolved.join(', ')}`)
}

main().catch(error => { console.error(error); process.exit(1) })
