var CveilWeb=(function(f){"use strict";var Rt=f=>{throw TypeError(f)};var Pt=(f,b,x)=>b.has(f)||Rt("Cannot "+x);var I=(f,b,x)=>(Pt(f,b,"read from private field"),x?x.call(f):b.get(f)),_t=(f,b,x)=>b.has(f)?Rt("Cannot add the same private member more than once"):b instanceof WeakSet?b.add(f):b.set(f,x),Ht=(f,b,x,R)=>(Pt(f,b,"write to private field"),R?R.call(f,x):b.set(f,x),x);var S;const b={consent_grants:{label:"Consent Grants",color:"#14b8a6"},consent_revocations:{label:"Consent Revocations",color:"#f59e0b"},consent_grant_all:{label:"Consent Grant All",color:"#0d9488"},consent_revoke_all:{label:"Consent Revoke All",color:"#d97706"},subject_created:{label:"Subject Created",color:"#3b82f6"},subject_updated:{label:"Subject Updated",color:"#6366f1"},subject_deleted:{label:"Subject Deleted",color:"#ef4444"},dsar_exports:{label:"DSAR Exports",color:"#8b5cf6"},dsar_deletions:{label:"DSAR Deletions",color:"#ec4899"},dsar_shred:{label:"DSAR Shred",color:"#a855f7"}},x={top:16,right:24,bottom:32,left:48};let R,O,re,Ie,B,be,P=new Set,_={};function Jt(e){const t=document.querySelector(e);if(!t)return;t.innerHTML="";const n=t.getBoundingClientRect(),s=n.width,a=n.height||360;R=d3.select(t).append("svg").attr("width",s).attr("height",a).attr("viewBox",`0 0 ${s} ${a}`).attr("preserveAspectRatio","xMidYMid meet"),B=R.append("g").attr("transform",`translate(${x.left},${x.top})`);const o=s-x.left-x.right,i=a-x.top-x.bottom;O=d3.scaleTime().range([0,o]),re=d3.scaleLinear().range([i,0]),B.append("g").attr("class","x-axis").attr("transform",`translate(0,${i})`),B.append("g").attr("class","y-axis"),Ie=d3.line().curve(d3.curveMonotoneX).x(d=>O(d.time)).y(d=>re(d.value)),be=d3.select(t).append("div").style("position","absolute").style("pointer-events","none").style("background","rgba(255,255,255,0.96)").style("border","1px solid #e5e7eb").style("border-radius","4px").style("padding","8px 12px").style("font-size","12px").style("box-shadow","0 2px 8px rgba(0,0,0,0.08)").style("display","none").style("z-index","10"),B.append("rect").attr("class","overlay").attr("width",o).attr("height",i).attr("fill","none").attr("pointer-events","all").on("mousemove",Ut).on("mouseleave",()=>be.style("display","none")),new ResizeObserver(()=>{const d=t.getBoundingClientRect();if(d.width>0){R.attr("width",d.width).attr("viewBox",`0 0 ${d.width} ${a}`);const u=d.width-x.left-x.right;O.range([0,u]),B.select(".overlay").attr("width",u),ie()}}).observe(t)}function Ft(e){_={};for(const t of e){const n=t.metricName;b[n]&&(_[n]||(_[n]=[]),_[n].push({time:new Date(t.timeBucket),value:t.metricValue}))}for(const t of Object.values(_))t.sort((n,s)=>n.time-s.time);ie()}function ie(){if(!B)return;const e={};for(const[o,i]of Object.entries(_))P.has(o)||(e[o]=i);const t=Object.values(e).flat();t.length===0?(O.domain([new Date,new Date]),re.domain([0,1])):(O.domain(d3.extent(t,o=>o.time)),re.domain([0,d3.max(t,o=>o.value)*1.1||1]).nice());const n=O.range()[1];B.select(".x-axis").transition().duration(300).call(d3.axisBottom(O).ticks(Math.min(n/80,10)).tickSizeOuter(0)).selectAll("text").attr("fill","#9ca3af").style("font-size","11px"),B.select(".y-axis").transition().duration(300).call(d3.axisLeft(re).ticks(5).tickSizeOuter(0)).selectAll("text").attr("fill","#9ca3af").style("font-size","11px"),B.selectAll(".domain").attr("stroke","#e5e7eb"),B.selectAll(".tick line").attr("stroke","#f3f4f6");const s=Object.entries(e),a=B.selectAll(".metric-line").data(s,o=>o[0]);a.exit().transition().duration(200).style("opacity",0).remove(),a.enter().append("path").attr("class","metric-line").attr("fill","none").attr("stroke-width",2).attr("stroke",o=>{var i;return((i=b[o[0]])==null?void 0:i.color)||"#999"}).attr("d",o=>Ie(o[1])).style("opacity",0).transition().duration(400).style("opacity",1),a.transition().duration(300).attr("d",o=>Ie(o[1])).attr("stroke",o=>{var i;return((i=b[o[0]])==null?void 0:i.color)||"#999"})}function Ut(e){if(!O||!_)return;const[t]=d3.pointer(e),n=O.invert(t);let s=`<div style="font-weight:600;margin-bottom:4px;color:#374151">${d3.timeFormat("%b %d, %Y %H:%M")(n)}</div>`,a=!1;for(const[d,u]of Object.entries(_)){if(P.has(d)||!u.length)continue;const y=d3.bisector(m=>m.time).left;let g=y(u,n);if(g>=u.length&&(g=u.length-1),g>0){const m=u[g-1],v=u[g];n-m.time<v.time-n&&(g=g-1)}const c=b[d];s+=`<div style="display:flex;align-items:center;gap:6px">
            <span style="width:8px;height:8px;border-radius:50%;background:${c.color};display:inline-block"></span>
            <span style="color:#6b7280">${c.label}:</span>
            <span style="font-weight:600;color:#111827">${Math.round(u[g].value).toLocaleString()}</span>
        </div>`,a=!0}if(!a){be.style("display","none");return}const o=R.node().parentElement.getBoundingClientRect();R.node().getBoundingClientRect();const i=e.clientX-o.left,l=e.clientY-o.top;be.html(s).style("display","block").style("left",`${Math.min(i+12,o.width-180)}px`).style("top",`${l-10}px`)}function Gt(e){const t=document.querySelector(e);if(t){t.innerHTML="";for(const[n,s]of Object.entries(b)){const a=document.createElement("button");a.className="flex items-center gap-1.5 text-xs transition-opacity cursor-pointer",a.dataset.metric=n,a.innerHTML=`<span style="width:10px;height:10px;border-radius:50%;background:${s.color};display:inline-block"></span>
            <span class="text-gray-600">${s.label}</span>`,a.onclick=()=>qt(n),t.appendChild(a)}}}function qt(e){P.has(e)?P.delete(e):P.add(e),Le(),ie()}function Le(){document.querySelectorAll("#chart-legend button").forEach(e=>{const t=e.dataset.metric;e.style.opacity=P.has(t)?"0.3":"1"})}function Wt(){P.clear(),Le(),ie()}function zt(){for(const e of Object.keys(b))P.add(e);Le(),ie()}const ke="/api/v1/metrics";let xe=7;const Yt=["consent_grants","consent_revocations","consent_grant_all","consent_revoke_all"],Vt=["subject_created","subject_updated","subject_deleted"],Kt=["dsar_exports","dsar_deletions","dsar_shred"];function Xt(){document.getElementById("usage-chart")&&(Jt("#usage-chart"),Gt("#chart-legend"),Ce())}function Zt(e){xe=e,document.querySelectorAll(".range-btn").forEach(a=>{a.classList.remove("border-cv-500","bg-cv-500","text-white"),a.classList.add("border-gray-300","text-gray-600")});const t=document.getElementById("range-"+e);t&&(t.classList.add("border-cv-500","bg-cv-500","text-white"),t.classList.remove("border-gray-300","text-gray-600"));const n=document.getElementById("range-from"),s=document.getElementById("range-to");n&&(n.value=""),s&&(s.value=""),Ce()}function Qt(){const e=document.getElementById("range-from"),t=document.getElementById("range-to");!(e!=null&&e.value)||!(t!=null&&t.value)||(document.querySelectorAll(".range-btn").forEach(n=>{n.classList.remove("border-cv-500","bg-cv-500","text-white"),n.classList.add("border-gray-300","text-gray-600")}),xe=0,Ce())}function en(){Wt()}function tn(){zt()}function nn(){const e=document.getElementById("range-from"),t=document.getElementById("range-to");if(xe===0&&(e!=null&&e.value)&&(t!=null&&t.value))return{from:new Date(e.value+"T00:00:00Z").toISOString(),to:new Date(t.value+"T23:59:59Z").toISOString()};const n=new Date;return{from:new Date(n.getTime()-xe*864e5).toISOString(),to:n.toISOString()}}async function Be(e){try{const t=await fetch(e);if(!t.ok)throw new Error(t.statusText);return await t.json()}catch(t){return console.warn("Metrics fetch failed:",e,t.message),null}}async function Ce(){const{from:e,to:t}=nn(),n=document.getElementById("chart-loading");n&&(n.textContent="Loading…");const[s,a,o]=await Promise.all([Be(`${ke}/summary?from=${e}&to=${t}`),Be(`${ke}?from=${e}&to=${t}&pageSize=500`),Be(`${ke}?from=${e}&to=${t}&metricName=api_requests&pageSize=500`)]);n&&(n.textContent=""),sn(s),an(a),on(o),rn(s)}function q(e,t){return e?e.filter(n=>t.includes(n.metricName)).reduce((n,s)=>n+s.totalValue,0):0}function sn(e){const t=(e==null?void 0:e.summaries)||[];W("card-total-requests",M(q(t,["api_requests"]))),W("card-consent-ops",M(q(t,Yt))),W("card-subject-ops",M(q(t,Vt))),W("card-dsar-ops",M(q(t,Kt))),W("card-rate-limits",M(q(t,["rate_limit_exceeded"])))}function an(e){const t=(e==null?void 0:e.data)||[];Ft(t)}function on(e){var o,i,l;const t=document.getElementById("top-endpoints-body");if(!t)return;const n=(e==null?void 0:e.data)||[];if(n.length===0){t.innerHTML='<tr><td colspan="3" class="py-6 text-center text-gray-400 text-sm">No data for this time range</td></tr>';return}const s={};for(const d of n){const u=((o=d.dimensions)==null?void 0:o.path)||((i=d.dimensions)==null?void 0:i.method)||"unknown";s[u]||(s[u]={count:0,latencySum:0,latencyCount:0}),s[u].count+=d.metricValue}for(const d of n)if(d.metricName==="api_latency_ms"){const u=((l=d.dimensions)==null?void 0:l.path)||"unknown";s[u]&&(s[u].latencySum+=d.metricValue,s[u].latencyCount+=1)}const a=Object.entries(s).map(([d,u])=>({path:d,count:u.count,avgLatency:u.latencyCount>0?Math.round(u.latencySum/u.latencyCount):"—"})).sort((d,u)=>u.count-d.count).slice(0,10);t.innerHTML=a.map(d=>`
        <tr class="border-b border-gray-50 hover:bg-gray-50/50">
            <td class="py-2.5 font-mono text-xs text-gray-700">${cn(d.path)}</td>
            <td class="py-2.5 text-right text-gray-900 font-medium">${M(d.count)}</td>
            <td class="py-2.5 text-right text-gray-500">${d.avgLatency==="—"?"—":d.avgLatency+" ms"}</td>
        </tr>
    `).join("")}function rn(e){const t=(e==null?void 0:e.summaries)||[],n=q(t,["api_requests"]),s=q(t,["subject_created"]),a=1e4,o=1e3;W("limit-requests",`${M(n)} / ${M(a)}`),Qe("limit-requests-bar",Math.min(100,n/a*100)),W("limit-subjects",`${M(s)} / ${M(o)}`),Qe("limit-subjects-bar",Math.min(100,s/o*100))}function W(e,t){const n=document.getElementById(e);n&&(n.textContent=t)}function Qe(e,t){const n=document.getElementById(e);n&&(n.style.width=t+"%")}function M(e){return e==null||isNaN(e)?"0":Math.round(e).toLocaleString()}function cn(e){const t=document.createElement("div");return t.textContent=e,t.innerHTML}const ln=Object.freeze(Object.defineProperty({__proto__:null,hideAllLines:tn,init:Xt,setCustomRange:Qt,setRange:Zt,showAllLines:en},Symbol.toStringTag,{value:"Module"}));function r(e){const t=document.createElement("div");return t.textContent=e,t.innerHTML}function ce(e){document.querySelectorAll('[id^="section-"]').forEach(n=>n.classList.add("hidden")),document.querySelectorAll(".tab-btn").forEach(n=>{n.classList.remove("border-cv-500","text-cv-500"),n.classList.add("border-transparent","text-gray-500")}),document.getElementById("section-"+e).classList.remove("hidden");const t=document.getElementById("tab-"+e);t.classList.add("border-cv-500","text-cv-500"),t.classList.remove("border-transparent","text-gray-400"),window.location.hash=e}function le(e){return async function(n,s={}){const a={...s.headers};s.body&&(a["Content-Type"]="application/json");const o=await fetch(e+n,{...s,headers:a});if(!o.ok){const i=await o.json().catch(()=>({message:o.statusText}));throw new Error(i.message||o.statusText)}return o.status===204?null:o.json()}}let he=null;function H(e,t){document.getElementById("delete-modal-msg").textContent=e,document.getElementById("delete-modal").classList.remove("hidden"),he=t}function ve(){document.getElementById("delete-modal").classList.add("hidden"),he=null}function Me(){document.getElementById("delete-modal-confirm").addEventListener("click",async()=>{he&&(await he(),ve())})}function Oe(){const e=document.getElementById("panel-overlay"),t=document.getElementById("panel");e.classList.remove("hidden"),requestAnimationFrame(()=>t.classList.remove("translate-x-full"))}function w(){document.getElementById("panel").classList.add("translate-x-full"),setTimeout(()=>document.getElementById("panel-overlay").classList.add("hidden"),300)}function de(e,t="gray"){const n={gray:"bg-gray-100 text-gray-700",teal:"bg-cv-50 text-cv-700 border border-cv-200",blue:"bg-blue-50 text-blue-700 border border-blue-200",purple:"bg-purple-50 text-purple-700 border border-purple-200"};return`<span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${n[t]||n.gray}">${r(e)}</span>`}function L(e,t,n=!1){e.textContent=t,e.className="text-sm mt-3 "+(n?"text-red-400":"text-green-400"),e.classList.remove("hidden"),setTimeout(()=>e.classList.add("hidden"),4e3)}function ue(e){return e?new Date(e).toLocaleString(void 0,{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"}):"—"}function Ae(e){document.readyState!=="loading"?e():document.addEventListener("DOMContentLoaded",e)}const dn={maxAttempts:5,initialDelay:60,maxDelay:500,backoff:2};async function z(e,t,n={}){const{maxAttempts:s,initialDelay:a,maxDelay:o,backoff:i}={...dn,...n},l=JSON.stringify(t);let d=a;for(let u=0;u<s;u++){await new Promise(y=>setTimeout(y,d));try{const y=await e();if(JSON.stringify(y)!==l)return y}catch{}d=Math.min(d*i,o)}try{return await e()}catch{return t}}function E(e,t){const n=document.getElementById(e);n&&(n.style.opacity=t?"0.45":"",n.style.pointerEvents=t?"none":"",n.style.transition="opacity 0.15s ease")}const N=le("/api/v1/catalogue");let Y=[],V=[],Z=[];async function De(){E("entities-table",!0),Y=await z(()=>N("/entities"),Y),tt(),E("entities-table",!1)}async function we(){E("fields-table",!0),V=await z(()=>N("/fields"),V),st(),E("fields-table",!1)}async function et(){E("categories-table",!0),Z=await z(()=>N("/categories"),Z),at(),E("categories-table",!1)}function Re(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"entity-add":()=>{n.textContent="Add Entity",s.innerHTML=nt()},"entity-edit":()=>{n.textContent="Edit Entity",s.innerHTML=nt(t)},"field-add":()=>{n.textContent="Add Field",s.innerHTML=yn()},"field-categories":()=>{n.textContent="Manage Categories",s.innerHTML=hn(t)},"category-add":()=>{n.textContent="Add Category",s.innerHTML=ot()},"category-edit":()=>{n.textContent="Edit Category",s.innerHTML=ot(t)}}[e]||(()=>{s.innerHTML=""}))(),Oe()}async function un(){try{Y=await N("/entities"),tt()}catch(e){document.getElementById("entities-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function tt(){const e=document.getElementById("entities-table");if(!Y.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No entities defined yet.</td></tr>';return}e.innerHTML=Y.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.entityName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("entity-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteEntity("${r(t.entityName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function nt(e){const t=e?r(e.entityName):"",n=e?r(e.description||""):"";return`
        <form onsubmit="saveEntity(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Name</label>
                    <input id="pf-ent-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-ent-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-ent-msg" class="hidden"></span>
        </form>`}async function pn(e,t,n){e.preventDefault();const s=document.getElementById("pf-ent-name").value.trim(),a=document.getElementById("pf-ent-desc").value.trim(),o=document.getElementById("pf-ent-msg");try{t?await N(`/entities/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await N("/entities",{method:"POST",body:JSON.stringify({entityName:s,description:a})}),w(),await De()}catch(i){L(o,i.message,!0)}}function gn(e){H(`Delete entity "${e}"? This cannot be undone. Entities with fields must have their fields removed first.`,async()=>{try{await N(`/entities/${encodeURIComponent(e)}`,{method:"DELETE"}),await De()}catch(t){alert(t.message)}})}async function mn(){try{V=await N("/fields"),st()}catch(e){document.getElementById("fields-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function st(){const e=document.getElementById("fields-table");if(!V.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No fields defined yet.</td></tr>';return}e.innerHTML=V.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.fieldName)}</td>
            <td class="px-6 py-4 text-gray-400">${t.entity?r(t.entity.entityName):'<span class="text-gray-600">—</span>'}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.categories||[]).map(n=>de(n.categoryName,"purple")).join("")}
                    <button onclick='openPanel("field-categories", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded-md text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteField("${t.entity?r(t.entity.entityName):""}", "${r(t.fieldName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function yn(){return`
        <form onsubmit="saveField(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Field Name</label>
                    <input id="pf-fld-name" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Entity</label>
                    <select id="pf-fld-entity" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select entity…</option>
                        ${Y.map(t=>`<option value="${r(t.entityName)}">${r(t.entityName)}</option>`).join("")}
                    </select>
                    ${Y.length?"":'<p class="text-xs text-amber-400 mt-1">No entities registered. Add an entity first.</p>'}
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-fld-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none"></textarea>
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Entity Description <span class="text-gray-600">(optional, used if entity is new)</span></label>
                    <textarea id="pf-fld-ent-desc" rows="2"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Create</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-fld-msg" class="hidden"></span>
        </form>`}async function fn(e){e.preventDefault();const t=document.getElementById("pf-fld-name").value.trim(),n=document.getElementById("pf-fld-entity").value,s=document.getElementById("pf-fld-desc").value.trim()||null,a=document.getElementById("pf-fld-ent-desc").value.trim()||null,o=document.getElementById("pf-fld-msg");try{await N("/fields",{method:"POST",body:JSON.stringify({fieldName:t,entityName:n,description:s,entityDescription:a})}),w(),await we(),await De()}catch(i){L(o,i.message,!0)}}function bn(e,t){H(`Delete field "${t}" from entity "${e}"? This cannot be undone.`,async()=>{try{await N(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}`,{method:"DELETE"}),await we()}catch(n){alert(n.message)}})}async function xn(){try{Z=await N("/categories"),at()}catch(e){document.getElementById("categories-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function hn(e){const t=e.entity?e.entity.entityName:"",n=new Set((e.categories||[]).map(a=>a.categoryName)),s=Z.filter(a=>!n.has(a.categoryName)).map(a=>`<option value="${r(a.categoryName)}">${r(a.categoryName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage categories for field <strong class="text-gray-900">${r(e.fieldName)}</strong> on entity <strong class="text-gray-900">${r(t)}</strong></p>
        <div id="fc-list" class="space-y-2 mb-6">
            ${(e.categories||[]).map(a=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <span class="text-sm text-gray-900">${r(a.categoryName)}</span>
                    <button onclick="unassignFieldCategory('${r(t)}','${r(e.fieldName)}','${r(a.categoryName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No categories assigned.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Assign a category</p>
        <form onsubmit="assignFieldCategory(event, '${r(t)}', '${r(e.fieldName)}')">
            <div class="flex gap-2">
                <select id="fc-select" class="flex-1 bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${s}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Assign</button>
            </div>
            <span id="fc-msg" class="hidden"></span>
        </form>`}async function vn(e,t,n){e.preventDefault();const s=document.getElementById("fc-select").value;if(s)try{await N("/fields/categories",{method:"POST",body:JSON.stringify({fieldName:n,entityName:t,categoryName:s})}),w(),await we();const a=V.find(o=>o.fieldName===n&&o.entity&&o.entity.entityName===t);a&&Re("field-categories",a)}catch(a){L(document.getElementById("fc-msg"),a.message,!0)}}async function wn(e,t,n){try{await N(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}/categories/${encodeURIComponent(n)}`,{method:"DELETE"}),w(),await we();const s=V.find(a=>a.fieldName===t&&a.entity&&a.entity.entityName===e);s&&Re("field-categories",s)}catch(s){alert(s.message)}}function at(){const e=document.getElementById("categories-table");if(!Z.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No categories defined yet.</td></tr>';return}e.innerHTML=Z.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.categoryName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("category-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteCategory("${r(t.categoryName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function ot(e){const t=e?r(e.categoryName):"",n=e?r(e.description||""):"";return`
        <form onsubmit="saveCategory(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Name</label>
                    <input id="pf-cat-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-cat-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-cat-msg" class="hidden"></span>
        </form>`}async function En(e,t,n){e.preventDefault();const s=document.getElementById("pf-cat-name").value.trim(),a=document.getElementById("pf-cat-desc").value.trim(),o=document.getElementById("pf-cat-msg");try{t?await N(`/categories/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await N("/categories",{method:"POST",body:JSON.stringify({categoryName:s,description:a})}),w(),await et()}catch(i){L(o,i.message,!0)}}function Nn(e){H(`Delete category "${e}"? This cannot be undone. Categories still assigned to fields must be unassigned first.`,async()=>{try{await N(`/categories/${encodeURIComponent(e)}`,{method:"DELETE"}),await et()}catch(t){alert(t.message)}})}function $n(){Object.assign(window,{showTab:ce,openPanel:Re,closePanel:w,closeDeleteModal:ve,saveEntity:pn,deleteEntity:gn,saveField:fn,deleteField:bn,assignFieldCategory:vn,unassignFieldCategory:wn,saveCategory:En,deleteCategory:Nn}),Me(),Ae(()=>{un(),mn(),xn();const e=window.location.hash.replace("#","");["entities","fields","categories"].includes(e)&&ce(e)})}const Sn=Object.freeze(Object.defineProperty({__proto__:null,init:$n},Symbol.toStringTag,{value:"Module"})),k=Object.freeze({Plaintext:"Plaintext",RedactAll:"RedactAll",RedactUpToLast4:"RedactUpToLast4",Hash256:"Hash256",RedactLocal:"RedactLocal"}),jn=Object.values(k);function Ee(e){return typeof e=="string"?"***":typeof e=="number"?(Number.isInteger(e),0):typeof e=="boolean"?!1:null}function Tn(e){if(typeof e=="string"){const t=[...e];if(t.length<=4)return"****";const n=t.length-4;return"*".repeat(n)+t.slice(n).join("")}return Ee(e)}async function In(e){if(typeof e=="string"||typeof e=="number"){const t=String(e),n=new TextEncoder().encode(t),s=await crypto.subtle.digest("SHA-256",n);return Array.from(new Uint8Array(s)).map(a=>a.toString(16).padStart(2,"0")).join("")}return Ee(e)}function Ln(e){if(typeof e=="string"){const t=e.indexOf("@");if(t>=0){const n=e.slice(t),s=Math.max(t,4);return"*".repeat(s)+n}}return Ee(e)}async function rt(e,t){switch(t){case k.Plaintext:return e;case k.RedactAll:return Ee(e);case k.RedactUpToLast4:return Tn(e);case k.Hash256:return In(e);case k.RedactLocal:return Ln(e);default:return e}}async function kn(e,t){return Pe(structuredClone(e),"",t)}async function Pe(e,t,n){if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const s of Object.keys(e)){const a=t?`${t}.${s}`:s,o=n[a];if(o&&o!==k.Plaintext){e[s]=await rt(e[s],o);continue}e[s]=await Pe(e[s],a,n)}else if(Array.isArray(e))for(let s=0;s<e.length;s++)e[s]=await Pe(e[s],t,n);else if(t){const s=n[t];if(s&&s!==k.Plaintext)return rt(e,s)}return e}function it(e,t=""){const n=[];if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const[s,a]of Object.entries(e)){const o=t?`${t}.${s}`:s;a!==null&&typeof a=="object"&&!Array.isArray(a)?n.push(...it(a,o)):n.push(o)}return n}const Bn=new TextEncoder,Cn=new TextDecoder;class pe extends Error{constructor(t){super(t),this.name="CveilWasmError"}}const Ze=class Ze{constructor(){_t(this,S,null)}static async load(t){const n=new Ze,s={env:{cveil_host_time_ms:()=>BigInt(Date.now())}};let a;if(typeof WebAssembly.instantiateStreaming=="function")a=(await WebAssembly.instantiateStreaming(fetch(t),s)).instance.exports;else{const i=await(await fetch(t)).arrayBuffer(),l=await WebAssembly.compile(i);a=(await WebAssembly.instantiate(l,s)).exports}return Ht(n,S,a),n}get exports(){return I(this,S)}writeString(t){const n=Bn.encode(t),s=I(this,S).cveil_alloc(n.length);return new Uint8Array(I(this,S).memory.buffer,s,n.length).set(n),[s,n.length]}readResult(){const t=I(this,S).cveil_result_ptr(),n=I(this,S).cveil_result_len();if(!t||!n)return"";const s=new Uint8Array(I(this,S).memory.buffer,t,n);return Cn.decode(s)}freeString(t,n){I(this,S).cveil_dealloc(t,n)}call0(t){const n=t(),s=this.readResult();if(I(this,S).cveil_free_result(),n!==0)throw new pe(s||"WASM call failed");return s}call1(t,n){const[s,a]=this.writeString(n);try{const o=t(s,a),i=this.readResult();if(I(this,S).cveil_free_result(),o!==0)throw new pe(i||"WASM call failed");return i}finally{this.freeString(s,a)}}call2(t,n,s){const[a,o]=this.writeString(n),[i,l]=this.writeString(s);try{const d=t(a,o,i,l),u=this.readResult();if(I(this,S).cveil_free_result(),d!==0)throw new pe(u||"WASM call failed");return u}finally{this.freeString(a,o),this.freeString(i,l)}}call4(t,n,s,a,o){const[i,l]=this.writeString(n),[d,u]=this.writeString(s),[y,g]=this.writeString(a),[c,m]=this.writeString(o);try{const v=t(i,l,d,u,y,g,c,m),p=this.readResult();if(I(this,S).cveil_free_result(),v!==0)throw new pe(p||"WASM call failed");return p}finally{this.freeString(i,l),this.freeString(d,u),this.freeString(y,g),this.freeString(c,m)}}call5(t,n,s,a,o,i){const[l,d]=this.writeString(n),[u,y]=this.writeString(s),[g,c]=this.writeString(a),[m,v]=this.writeString(o),[p,T]=this.writeString(i);try{const D=t(l,d,u,y,g,c,m,v,p,T),oe=this.readResult();if(I(this,S).cveil_free_result(),D!==0)throw new pe(oe||"WASM call failed");return oe}finally{this.freeString(l,d),this.freeString(u,y),this.freeString(g,c),this.freeString(m,v),this.freeString(p,T)}}};S=new WeakMap;let _e=Ze;const He=le("/api/v1/catalogue"),Mn=le("/api/v1/governance"),Je=le("/api/v1/subjects");let ct=[],lt=[],On=[],dt=[],J=[],K={},X=null,Q=null,ut=!1,C=null,Ne=!1;const An="/wasm/cveil_sdk_core.wasm",pt={User:{name:"Alice Smith",email:"alice@example.com",ssn:"123-45-6789",age:30,address:{street:"123 Main St",city:"Springfield",zip:"62704"}},Patient:{patientId:"P-78291",firstName:"John",lastName:"Doe",dateOfBirth:"1985-03-14",diagnosis:"Hypertension",insurance:{provider:"BlueCross",policyNumber:"BC-44921-X"}},Order:{orderId:"ORD-10042",customerEmail:"jane@shop.com",phone:"+1-555-867-5309",total:149.99,items:[{sku:"WIDGET-A",name:"Widget Alpha",qty:2}],shipping:{street:"456 Oak Ave",city:"Portland",state:"OR"}}};async function Dn(){var e;if(!Ne)try{C=await _e.load(An);const t=C.exports;C.call1(t.cveil_init,JSON.stringify({apiBaseUrl:window.location.origin,apiToken:"",defaultJurisdiction:""}));let n=null,s=!0;for(;s;){const a=n?`/api/v1/consent/graph?limit=500&cursor=${encodeURIComponent(n)}`:"/api/v1/consent/graph?limit=500",o=await fetch(a);if(!o.ok)throw new Error("Graph fetch failed");const i=await o.json();s=JSON.parse(C.call1(t.cveil_ingest_graph_page,JSON.stringify(i))).hasMore,n=((e=i.pageInfo)==null?void 0:e.nextCursor)||null,n||(s=!1)}Ne=!0,yt(!0),$("WASM engine loaded — using real masking SDK",!1),setTimeout(()=>$("",!1),3e3)}catch(t){console.warn("WASM init failed, falling back to JS masking:",t),yt(!1),$("WASM unavailable — using JS masking fallback",!1),setTimeout(()=>$("",!1),4e3)}}async function Rn(){if(!ut){ut=!0;try{const[e,t,n,s]=await Promise.all([He("/entities"),Mn("/purposes"),He("/categories"),Je(""),Dn()]);ct=e||[],lt=t||[],On=n||[],dt=Array.isArray(s)?s:(s==null?void 0:s.items)||[],Fe("pg-subject",dt.map(a=>a.name||a.subjectName||a)),Fe("pg-entity",ct.map(a=>a.name||a.entityName||a)),Fe("pg-purpose",lt.map(a=>a.name||a.purposeName||a)),Pn()}catch(e){$(`Failed to load reference data: ${e.message}`,!0)}}}function Fe(e,t){const n=document.getElementById(e);if(!n)return;const s=n.value;n.innerHTML='<option value="">— select —</option>'+t.map(a=>`<option value="${r(String(a))}">${r(String(a))}</option>`).join(""),s&&t.includes(s)&&(n.value=s)}function Pn(){const e=document.getElementById("pg-templates");e&&(e.innerHTML=Object.keys(pt).map(t=>`<button onclick="loadSampleTemplate('${t}')"
            class="px-2 py-1 text-xs border border-gray-300 rounded text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">${r(t)}</button>`).join(""))}function _n(e){const t=pt[e];if(!t)return;const n=document.getElementById("pg-json-input");n&&(n.value=JSON.stringify(t,null,2)),Wn()}async function Hn(){var l,d,u,y,g;const e=(l=document.getElementById("pg-subject"))==null?void 0:l.value,t=(d=document.getElementById("pg-entity"))==null?void 0:d.value,n=(u=document.getElementById("pg-purpose"))==null?void 0:u.value,s=(g=(y=document.getElementById("pg-json-input"))==null?void 0:y.value)==null?void 0:g.trim();if(!e)return $("Select a subject.",!0);if(!t)return $("Select an entity.",!0);if(!n)return $("Select a purpose.",!0);if(!s)return $("Enter JSON data.",!0);let a;try{a=JSON.parse(s)}catch(c){return $(`Invalid JSON: ${c.message}`,!0)}X=a;const o=it(a);if(o.length===0)return $("No leaf fields found in JSON.",!0);$("Evaluating consent…",!1);const i=document.getElementById("pg-evaluate-btn");i&&(i.disabled=!0);try{Ne?await Jn(e,t,n,a,o):await Un(e,t,n,a,o),$("",!1)}catch(c){$(`Evaluate failed: ${c.message}`,!0)}finally{i&&(i.disabled=!1)}}async function Jn(e,t,n,s,a){const o=C.exports;let i=C.call4(o.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(i);if(l.needsFetch&&l.needsFetch.length>0&&[...new Set(l.needsFetch.flatMap(g=>g.policyNames||[]))].length>0){const g=a.map(c=>({fieldName:c.split(".").pop(),entityName:t,purposeName:n}));try{const c=await Je(`/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:g})}),m=Fn(c);m.length>0&&C.call2(o.cveil_ingest_decisions,e,JSON.stringify(m)),i=C.call4(o.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(i)}catch(c){console.warn("Decision fetch failed, using deny-by-default masking:",c)}}Q=l.data;const d=C.call4(o.cveil_evaluate,e,t,JSON.stringify(a),n),u=JSON.parse(d);J=a.map((y,g)=>{var p;const c=u[g]||{},m=c.masking_type||c.maskingType||k.RedactAll,v=c.needs_fetch||c.needsFetch||!1;return{path:y,fieldName:c.field||y.split(".").pop(),status:m===k.Plaintext?"GRANTED":"DENIED",resolvedBy:v?"PENDING":(p=c.policy_names)!=null&&p.length?"POLICY":"GRAPH",maskingType:m,detail:c}}),K={},mt(),Ue(l.data)}function Fn(e){const t=[],n=e.details||e.reasons||{},s=new Set((e.granted||[]).map(a=>a.fieldName||a));for(const[a,o]of Object.entries(n))(o.policyName||o.policy_name)&&t.push({policyName:o.policyName||o.policy_name,action:s.has(a)?"granted":"denied",expiresAt:o.expiresAt||o.expires_at||null});return t}async function Un(e,t,n,s,a){let o=[];try{o=await He(`/entities/${encodeURIComponent(t)}/fields`)||[]}catch{}const i={};for(const g of o)i[g.name||g.fieldName]=g.categoryName||g.category||"";const l=a.map(g=>{const c=g.split(".").pop();return{fieldName:c,entityName:t,purposeName:n,category:i[c]||""}}),d=await Je(`/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:l})}),u=new Set((d.granted||[]).map(g=>g.fieldName||g)),y=d.details||d.reasons||{};J=a.map(g=>{const c=g.split(".").pop(),m=u.has(c),v=y[c]||y[g]||{},p=v.maskingType||(m?k.Plaintext:k.RedactAll);return{path:g,fieldName:c,status:m?"GRANTED":"DENIED",resolvedBy:v.resolvedBy||"NONE",maskingType:p,detail:v}}),K={},mt(),await gt()}async function gt(){if(!X||J.length===0)return;const e={};for(const n of J)e[n.path]=K[n.path]||n.maskingType;const t=await kn(X,e);Q=t,Ue(t)}async function Gn(){var t,n,s;for(const a of J){const o=document.getElementById(`pg-override-${CSS.escape(a.path)}`);o&&o.value&&(K[a.path]=o.value)}const e=Object.keys(K).length>0;if(Ne&&!e){const a=(t=document.getElementById("pg-subject"))==null?void 0:t.value,o=(n=document.getElementById("pg-entity"))==null?void 0:n.value,i=(s=document.getElementById("pg-purpose"))==null?void 0:s.value;if(a&&o&&i&&X)try{const l=C.exports,d=C.call4(l.cveil_mask,a,o,JSON.stringify(X),i),u=JSON.parse(d);Q=u.data,Ue(u.data);return}catch{}}await gt()}function mt(){const e=document.getElementById("pg-decisions");if(!e||J.length===0)return;const t=J.map(n=>{const s=K[n.path]||n.maskingType,a=n.status==="GRANTED"?'<span class="inline-flex items-center gap-1 text-xs font-medium text-green-400"><span class="w-1.5 h-1.5 rounded-full bg-green-500"></span>GRANTED</span>':'<span class="inline-flex items-center gap-1 text-xs font-medium text-red-400"><span class="w-1.5 h-1.5 rounded-full bg-red-500"></span>DENIED</span>',o=jn.map(i=>`<option value="${i}" ${i===s?"selected":""}>${i}</option>`).join("");return`<tr class="border-t border-gray-200">
            <td class="px-3 py-2 font-mono text-xs text-gray-600">${r(n.path)}</td>
            <td class="px-3 py-2">${a}</td>
            <td class="px-3 py-2 text-xs text-gray-500">${r(n.resolvedBy)}</td>
            <td class="px-3 py-2">
                <select id="pg-override-${CSS.escape(n.path)}" onchange="remask()"
                    class="bg-white border border-gray-300 rounded-md px-2 py-1 text-xs text-gray-700 focus:border-cv-500 focus:outline-none">
                    ${o}
                </select>
            </td>
        </tr>`}).join("");e.innerHTML=`<table class="w-full text-sm">
        <thead class="text-xs text-gray-500 uppercase">
            <tr>
                <th class="px-3 py-2 text-left">Field Path</th>
                <th class="px-3 py-2 text-left">Status</th>
                <th class="px-3 py-2 text-left">Resolved By</th>
                <th class="px-3 py-2 text-left">Masking Type</th>
            </tr>
        </thead>
        <tbody>${t}</tbody>
    </table>`}function Ue(e){const t=document.getElementById("pg-masked-output");if(!t)return;const n=JSON.stringify(e,null,2),a=JSON.stringify(X,null,2).split(`
`),o=n.split(`
`);let i="";for(let l=0;l<o.length;l++){const d=l<a.length?a[l]:"",u=o[l];d!==u?i+=`<div class="bg-red-900/20 text-red-300">${r(u)}</div>`:i+=`<div class="text-gray-300">${r(u)}</div>`}t.innerHTML=`<div class="flex items-center justify-between mb-2">
        <span class="text-xs text-gray-500 uppercase tracking-wide">Masked Output</span>
        <button onclick="copyMaskedOutput()" class="text-xs text-cv-500 hover:text-cv-400 transition-colors">Copy</button>
    </div>
    <pre class="font-mono text-xs leading-relaxed overflow-auto" style="max-height:340px">${i}</pre>`}function qn(){Q&&(navigator.clipboard.writeText(JSON.stringify(Q,null,2)),$("Copied to clipboard!",!1),setTimeout(()=>$("",!1),2e3))}function Wn(){J=[],K={},X=null,Q=null;const e=document.getElementById("pg-decisions"),t=document.getElementById("pg-masked-output");e&&(e.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Run an evaluation to see consent decisions.</p>'),t&&(t.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Masked output will appear here.</p>')}function yt(e){const t=document.getElementById("pg-wasm-badge");t&&(e?(t.textContent="WASM SDK",t.className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700"):(t.textContent="JS Fallback",t.className="text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700"))}function $(e,t){const n=document.getElementById("playground-msg");if(n){if(!e){n.className="hidden",n.textContent="";return}n.className=`text-xs px-3 py-2 rounded-md ${t?"bg-red-50 text-red-600":"bg-gray-100 text-gray-500"}`,n.textContent=e}}const ft="/api/v1/consent/graph",bt={purpose:"#14b8a6",regulation:"#3b82f6",jurisdiction:"#f59e0b",field:"#a855f7",subject:"#ec4899"};let xt=!1,ht="force",j={nodes:[],edges:[]},ge=null,Ge=!1,me=null;const zn="/api/v1/events/invalidations";let ee=null,vt=!1,te=!1,ne=[];const Yn=500,qe={GRANTED:{badge:"bg-green-900/60 text-green-400 border border-green-500/30",dot:"bg-green-500",label:"Granted"},REVOKED:{badge:"bg-red-900/60 text-red-400 border border-red-500/30",dot:"bg-red-500",label:"Revoked"},CONSENT_ENTRY_CHANGED:{badge:"bg-amber-900/60 text-amber-400 border border-amber-500/30",dot:"bg-amber-500",label:"Changed"}},F="/api/v1/subjects";let $e=[],We=!1;async function Vn(){const e=await fetch(F);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);return e.json()}async function wt(){E("subjects-table",!0),$e=await z(Vn,$e),We=!0,Ve(),E("subjects-table",!1)}let U=null;function Et(e){ce(e),e==="graph"&&!xt&&Se(),e==="ledger"&&ts(),e==="subjects"&&!We&&rs(),e==="playground"&&Rn()}function ze(e){return e==="GRANTED"?qe.GRANTED:e==="REVOKED"||e==="ALL_REVOKED"?qe.REVOKED:qe.CONSENT_ENTRY_CHANGED}function Kn(e){ht=e,document.getElementById("graph-mode-force").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="force"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),document.getElementById("graph-mode-tree").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="tree"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),Ye()}async function Se(){var s,a;const e=document.getElementById("graph-search").value.trim(),t=document.getElementById("graph-root-type").value,n=new URLSearchParams;n.set("limit","200"),e&&t?(n.set("rootType",t),n.set("rootName",e),n.set("depth","3")):e&&(n.set("rootName",e),n.set("depth","3")),document.getElementById("graph-loading").classList.remove("hidden"),document.getElementById("graph-empty").classList.add("hidden");try{const o=await fetch(`${ft}?${n}`);if(!o.ok)throw new Error((await o.json().catch(()=>({}))).message||o.statusText);const i=await o.json();if(j=i,ge=((s=i.pageInfo)==null?void 0:s.nextCursor)||null,Ge=((a=i.pageInfo)==null?void 0:a.hasMore)||!1,xt=!0,!i.nodes||i.nodes.length===0){document.getElementById("graph-loading").classList.add("hidden"),document.getElementById("graph-empty").classList.remove("hidden"),document.getElementById("graph-pagination").classList.add("hidden");return}Ye(),Nt(),document.getElementById("graph-loading").classList.add("hidden")}catch(o){document.getElementById("graph-loading").innerHTML=`<span class="text-red-400 text-sm">${r(o.message)}</span>`}}async function Xn(){var t,n;if(!ge)return;const e=new URLSearchParams;e.set("limit","200"),e.set("cursor",ge);try{const a=await(await fetch(`${ft}?${e}`)).json(),o=new Set(j.nodes.map(l=>l.type+":"+l.name));a.nodes.forEach(l=>{o.has(l.type+":"+l.name)||j.nodes.push(l)});const i=new Set(j.edges.map(l=>l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName));a.edges.forEach(l=>{i.has(l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName)||j.edges.push(l)}),ge=((t=a.pageInfo)==null?void 0:t.nextCursor)||null,Ge=((n=a.pageInfo)==null?void 0:n.hasMore)||!1,Ye(),Nt()}catch(s){console.error("Failed to load more graph:",s)}}function Zn(){document.getElementById("graph-search").value="",document.getElementById("graph-root-type").value="",ge=null,Se()}function Nt(){const e=document.getElementById("graph-pagination"),t=document.getElementById("graph-stats"),n=j.pageInfo||{};t.textContent=`${j.nodes.length} nodes, ${j.edges.length} edges`+(n.totalNodes?` (of ${n.totalNodes} total)`:""),e.classList.toggle("hidden",!Ge)}function Ye(){ht==="force"?Qn():es()}function Qn(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,a=n.clientHeight;t.attr("viewBox",[0,0,s,a]);const o=new Map;j.nodes.forEach((c,m)=>{o.set(c.type+":"+c.name,m)});const i=j.nodes.map(c=>({id:c.type+":"+c.name,type:c.type,name:c.name,properties:c.properties||{}})),l=j.edges.filter(c=>o.has(c.sourceType+":"+c.sourceName)&&o.has(c.targetType+":"+c.targetName)).map(c=>({source:c.sourceType+":"+c.sourceName,target:c.targetType+":"+c.targetName,properties:c.properties||{}})),d=t.append("g");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",c=>{d.attr("transform",c.transform)}));const u=d.append("g").selectAll("line").data(l).join("line").attr("stroke","#374151").attr("stroke-width",1).attr("stroke-opacity",.6),y=d.append("g").selectAll("circle").data(i).join("circle").attr("r",c=>c.type==="subject"?6:8).attr("fill",c=>bt[c.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5).style("cursor","pointer").call(e.drag().on("start",(c,m)=>{c.active||me.alphaTarget(.3).restart(),m.fx=m.x,m.fy=m.y}).on("drag",(c,m)=>{m.fx=c.x,m.fy=c.y}).on("end",(c,m)=>{c.active||me.alphaTarget(0),m.fx=null,m.fy=null})),g=d.append("g").selectAll("text").data(i).join("text").text(c=>c.name.length>20?c.name.slice(0,18)+"…":c.name).attr("font-size",10).attr("fill","#9ca3af").attr("dx",12).attr("dy",4).style("pointer-events","none");y.on("mouseover",(c,m)=>{const v=document.getElementById("graph-tooltip");document.getElementById("tooltip-type").textContent=m.type,document.getElementById("tooltip-name").textContent=m.name;const p=document.getElementById("tooltip-props");p.innerHTML=Object.entries(m.properties).map(([T,D])=>`<div class="flex justify-between gap-4"><span class="text-gray-500">${r(T)}</span><span class="text-white">${r(String(D))}</span></div>`).join(""),v.classList.remove("hidden"),v.style.left=c.pageX+12+"px",v.style.top=c.pageY-10+"px"}).on("mouseout",()=>{document.getElementById("graph-tooltip").classList.add("hidden")}),me&&me.stop(),me=e.forceSimulation(i).force("link",e.forceLink(l).id(c=>c.id).distance(80)).force("charge",e.forceManyBody().strength(-200)).force("center",e.forceCenter(s/2,a/2)).force("collision",e.forceCollide(15)).on("tick",()=>{u.attr("x1",c=>c.source.x).attr("y1",c=>c.source.y).attr("x2",c=>c.target.x).attr("y2",c=>c.target.y),y.attr("cx",c=>c.x).attr("cy",c=>c.y),g.attr("x",c=>c.x).attr("y",c=>c.y)})}function es(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,a=n.clientHeight;t.attr("viewBox",[0,0,s,a]);const o=["jurisdiction","regulation","purpose","field","subject"],i={name:"Consent Graph",type:"root",children:[]},l={};o.forEach(p=>{l[p]=[]}),j.nodes.forEach(p=>{l[p.type]?l[p.type].push({name:p.name,type:p.type,properties:p.properties||{}}):(l.other||(l.other=[]),l.other.push({name:p.name,type:p.type,properties:p.properties||{}}))});const d=new Map;j.edges.forEach(p=>{const T=p.sourceType+":"+p.sourceName,D=p.targetType+":"+p.targetName;d.has(T)||d.set(T,[]),d.get(T).push(D)});const u=new Set;function y(p){if(u.has(p))return null;u.add(p);const[T,...D]=p.split(":"),fe={name:D.join(":"),type:T,children:[]};return(d.get(p)||[]).forEach(Js=>{const Dt=y(Js);Dt&&fe.children.push(Dt)}),fe}if(o.forEach(p=>{var T;(T=l[p])==null||T.forEach(D=>{const oe=p+":"+D.name;if(!u.has(oe)){const fe=y(oe);fe&&i.children.push(fe)}})}),i.children.length===0){document.getElementById("graph-empty").classList.remove("hidden");return}const g=e.hierarchy(i);e.tree().size([a-40,s-200])(g);const m=t.append("g").attr("transform","translate(80, 20)");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",p=>{m.attr("transform",p.transform)})),m.selectAll(".tree-link").data(g.links()).join("path").attr("class","tree-link").attr("d",e.linkHorizontal().x(p=>p.y).y(p=>p.x)).attr("fill","none").attr("stroke","#374151").attr("stroke-width",1.5);const v=m.selectAll(".tree-node").data(g.descendants()).join("g").attr("class","tree-node").attr("transform",p=>`translate(${p.y},${p.x})`);v.append("circle").attr("r",6).attr("fill",p=>bt[p.data.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5),v.append("text").attr("dy",4).attr("x",p=>p.children?-10:10).attr("text-anchor",p=>p.children?"end":"start").attr("font-size",10).attr("fill","#9ca3af").text(p=>p.data.name==="Consent Graph"?"":p.data.name.length>25?p.data.name.slice(0,23)+"…":p.data.name)}function ts(){if(!vt){vt=!0;try{ee=new EventSource(zn),ee.onopen=()=>{je("connected")},ee.onmessage=e=>{try{const t=JSON.parse(e.data);if(!["GRANTED","REVOKED","ALL_REVOKED","CONSENT_ENTRY_CHANGED"].includes(t.action))return;ns(t)}catch(t){console.warn("Failed to parse SSE event:",t)}},ee.onerror=()=>{je("disconnected"),setTimeout(()=>{ee&&ee.readyState!==EventSource.CLOSED&&je("reconnecting")},2e3)}}catch(e){je("error"),console.error("SSE initialization failed:",e)}}}function je(e){const t=document.getElementById("sse-status"),n={connected:{dot:"bg-green-500",text:"text-green-400",label:"Live"},disconnected:{dot:"bg-red-500",text:"text-red-400",label:"Disconnected"},reconnecting:{dot:"bg-amber-500 animate-pulse",text:"text-amber-400",label:"Reconnecting…"},error:{dot:"bg-red-500",text:"text-red-400",label:"Error"}},s=n[e]||n.disconnected;t.innerHTML=`<span class="w-2 h-2 rounded-full ${s.dot}"></span><span class="${s.text}">${s.label}</span>`}function ns(e){const t={...e,receivedAt:new Date().toISOString()};ne.unshift(t),ne.length>Yn&&ne.pop(),$t()}function $t(){const e=document.getElementById("ledger-feed"),t=document.getElementById("ledger-empty"),n=ss();if(n.length===0){t.classList.remove("hidden"),e.querySelectorAll(".ledger-event").forEach(s=>s.remove()),document.getElementById("ledger-count").textContent="";return}t.classList.add("hidden"),e.innerHTML=n.map(s=>{const a=ze(s.action);return`
            <div class="ledger-event flex items-start gap-4 px-5 py-3 hover:bg-gray-50 transition-colors">
                <div class="mt-1.5 flex-shrink-0">
                    <span class="block w-2.5 h-2.5 rounded-full ${a.dot}"></span>
                </div>
                <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2 mb-1">
                        <span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${a.badge}">${a.label}</span>
                        ${s.subject?`<span class="text-xs text-white font-mono truncate">${r(s.subject)}</span>`:""}
                    </div>
                    <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                        ${s.purpose?`<span>Purpose: <span class="text-gray-300">${r(s.purpose)}</span></span>`:""}
                        ${s.field?`<span>Field: <span class="text-gray-300">${r(s.field)}</span></span>`:""}
                        ${s.entity?`<span>Entity: <span class="text-gray-300">${r(s.entity)}</span></span>`:""}
                        ${s.category?`<span>Category: <span class="text-gray-300">${r(s.category)}</span></span>`:""}
                    </div>
                </div>
                <div class="flex-shrink-0 text-xs text-gray-600 whitespace-nowrap">
                    ${ue(s.receivedAt)}
                </div>
            </div>`}).join(""),document.getElementById("ledger-count").textContent=`${n.length} events shown`+(n.length<ne.length?` (${ne.length} total)`:""),te||(e.scrollTop=0)}function ss(){const e=document.getElementById("ledger-filter-action").value,t=document.getElementById("ledger-filter-subject").value.trim().toLowerCase(),n=document.getElementById("ledger-filter-purpose").value.trim().toLowerCase();return ne.filter(s=>!(e&&s.action!==e||t&&(!s.subject||!s.subject.toLowerCase().includes(t))||n&&(!s.purpose||!s.purpose.toLowerCase().includes(n))))}function as(){$t()}function os(){te=!te;const e=document.getElementById("ledger-pause-btn");e.classList.toggle("text-cv-500",te),e.classList.toggle("border-cv-500/30",te),e.title=te?"Resume auto-scroll":"Pause auto-scroll"}async function rs(){try{const e=await fetch(F);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);$e=await e.json(),We=!0,Ve()}catch(e){document.getElementById("subjects-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function Ve(e){const t=document.getElementById("subjects-table");let n=$e;if(e){const s=e.toLowerCase();n=n.filter(a=>a.subjectName.toLowerCase().includes(s)||(a.description||"").toLowerCase().includes(s))}if(!n.length){t.innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">${e?"No matching subjects.":"No subjects registered yet."}</td></tr>`;return}t.innerHTML=n.map(s=>`
        <tr class="hover:bg-gray-50 transition-colors cursor-pointer" onclick="viewSubject('${r(s.subjectName)}')">
            <td class="px-6 py-4 font-medium text-gray-900 font-mono">${r(s.subjectName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(s.description||"—")}</td>
            <td class="px-6 py-4 text-gray-500 text-xs">${ue(s.createdAt)}</td>
            <td class="px-6 py-4 text-right" onclick="event.stopPropagation()">
                <button onclick='openSubjectPanel("edit", ${JSON.stringify(s).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteSubject("${r(s.subjectName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function is(){Ve(document.getElementById("subjects-search").value.trim())}async function cs(e){U=e,document.getElementById("subject-detail").classList.remove("hidden"),document.getElementById("subject-detail-title").textContent=e,jt("summary")}function St(){document.getElementById("subject-detail").classList.add("hidden"),U=null}function jt(e){document.querySelectorAll(".dtab-btn").forEach(t=>{t.classList.remove("border-cv-500","text-cv-500"),t.classList.add("border-transparent","text-gray-400")}),document.getElementById("dtab-"+e).classList.add("border-cv-500","text-cv-500"),document.getElementById("dtab-"+e).classList.remove("border-transparent","text-gray-400"),e==="summary"?ls():e==="history"&&ds()}async function ls(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${F}/${encodeURIComponent(U)}/summary`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No active consent entries for this subject.</p>';return}e.innerHTML=`
            <div class="space-y-0 divide-y divide-gray-700/30">
                ${s.map(a=>`
                    <div class="flex items-center gap-4 py-3">
                        <span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${ze(a.action).badge}">${r(a.action)}</span>
                        <div class="flex-1 text-sm">
                            <span class="text-white">${r(a.purposeName)}</span>
                            <span class="text-gray-500 mx-1">→</span>
                            <span class="text-gray-400">${r(a.policyType==="FIELD"?(a.entityName?a.entityName+"/":"")+(a.fieldName||""):a.categoryName||"")}</span>
                        </div>
                        <span class="text-xs text-gray-600">${ue(a.createdAt)}</span>
                        ${a.expiresAt?`<span class="text-xs text-amber-500/70">expires ${ue(a.expiresAt)}</span>`:""}
                    </div>`).join("")}
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${r(t.message)}</p>`}}async function ds(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${F}/${encodeURIComponent(U)}/history`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No history for this subject.</p>';return}e.innerHTML=`
            <div class="overflow-x-auto">
                <table class="w-full text-xs text-left">
                    <thead class="text-gray-500 uppercase tracking-wide">
                        <tr>
                            <th class="py-2 pr-4">Action</th>
                            <th class="py-2 pr-4">Purpose</th>
                            <th class="py-2 pr-4">Scope</th>
                            <th class="py-2 pr-4">Actor</th>
                            <th class="py-2 pr-4">IP</th>
                            <th class="py-2">Time</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-200">
                        ${s.map(a=>`
                            <tr class="hover:bg-gray-50">
                                <td class="py-2 pr-4"><span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${ze(a.action).badge}">${r(a.action)}</span></td>
                                <td class="py-2 pr-4 text-gray-900">${r(a.purposeName)}</td>
                                <td class="py-2 pr-4 text-gray-600">${r(a.policyType==="FIELD"?(a.entityName?a.entityName+"/":"")+(a.fieldName||""):a.categoryName||"")}</td>
                                <td class="py-2 pr-4 text-gray-500">${r(a.actor||"—")}</td>
                                <td class="py-2 pr-4 text-gray-500 font-mono">${r(a.ipAddress||"—")}</td>
                                <td class="py-2 text-gray-500">${ue(a.createdAt)}</td>
                            </tr>`).join("")}
                    </tbody>
                </table>
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${r(t.message)}</p>`}}async function us(){if(U)try{const e=await fetch(`${F}/${encodeURIComponent(U)}/export`);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);const t=await e.json(),n=new Blob([JSON.stringify(t,null,2)],{type:"application/json"}),s=URL.createObjectURL(n),a=document.createElement("a");a.href=s,a.download=`dsar-export-${U}.json`,a.click(),URL.revokeObjectURL(s)}catch(e){alert("Export failed: "+e.message)}}function ps(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");e==="add"?(n.textContent="Register Subject",s.innerHTML=It()):e==="edit"&&(n.textContent="Edit Subject",s.innerHTML=It(t)),Oe()}function Tt(){w()}function It(e){const t=e?r(e.subjectName):"",n=e?r(e.description||""):"";return`
        <form onsubmit="saveSubject(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Subject Name</label>
                    <input id="pf-sub-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-sub-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Register"}
                </button>
                <button type="button" onclick="closeSubjectPanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-sub-msg" class="hidden"></span>
        </form>`}async function gs(e,t,n){e.preventDefault();const s=document.getElementById("pf-sub-name").value.trim(),a=document.getElementById("pf-sub-desc").value.trim(),o=document.getElementById("pf-sub-msg");try{if(t){const i=await fetch(`${F}/${encodeURIComponent(n)}`,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({description:a})});if(!i.ok)throw new Error((await i.json().catch(()=>({}))).message||i.statusText)}else{const i=await fetch(F,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({subjectName:s,description:a})});if(!i.ok)throw new Error((await i.json().catch(()=>({}))).message||i.statusText)}Tt(),await wt()}catch(i){o.textContent=i.message,o.className="text-sm mt-3 text-red-400",o.classList.remove("hidden")}}function ms(e){H(`Delete subject "${e}"? This cannot be undone.`,async()=>{try{const t=await fetch(`${F}/${encodeURIComponent(e)}`,{method:"DELETE"});if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);await wt(),U===e&&St()}catch(t){alert(t.message)}})}function ys(){Object.assign(window,{showTab:Et,closeDeleteModal:ve,setGraphMode:Kn,loadGraph:Se,resetGraph:Zn,loadMoreGraph:Xn,applyLedgerFilters:as,toggleLedgerPause:os,filterSubjects:is,openSubjectPanel:ps,closeSubjectPanel:Tt,viewSubject:cs,closeSubjectDetail:St,showDetailTab:jt,exportSubjectData:us,saveSubject:gs,deleteSubject:ms,evaluateAndMask:Hn,remask:Gn,loadSampleTemplate:_n,copyMaskedOutput:qn}),Me(),Ae(()=>{const e=window.location.hash.replace("#","");["graph","ledger","subjects","playground"].includes(e)?Et(e):Se()})}const fs=Object.freeze(Object.defineProperty({__proto__:null,init:ys},Symbol.toStringTag,{value:"Module"})),h=le("/api/v1/governance"),Lt="/api/v1/catalogue";let Ke=[],Xe=[],se=[],G=[],A=[];async function bs(){try{const[e,t]=await Promise.all([fetch(Lt+"/fields").then(n=>n.ok?n.json():[]),fetch(Lt+"/categories").then(n=>n.ok?n.json():[])]);Ke=e,Xe=t}catch(e){console.warn("Failed to load catalogue data:",e)}}async function kt(){E("jurisdictions-table",!0),se=await z(()=>h("/jurisdictions"),se),Bt(),E("jurisdictions-table",!1)}async function Te(){E("regulations-table",!0),G=await z(()=>h("/regulations"),G),Mt(),E("regulations-table",!1)}async function ae(){E("purposes-table",!0),E("policies-table",!0),A=await z(()=>h("/purposes"),A),Ot(),At(),E("purposes-table",!1),E("policies-table",!1)}function ye(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"jurisdiction-add":()=>{n.textContent="Add Jurisdiction",s.innerHTML=Ct()},"jurisdiction-edit":()=>{n.textContent="Edit Jurisdiction",s.innerHTML=Ct(t)},"regulation-add":()=>{n.textContent="Add Regulation",s.innerHTML=Es()},"purpose-add":()=>{n.textContent="Add Purpose",s.innerHTML=Ls()},"policy-add":()=>{n.textContent="Add Policy",s.innerHTML=As()},"regulation-jurisdictions":()=>{n.textContent="Manage Jurisdictions",s.innerHTML=Ss(t)},"purpose-regulations":()=>{n.textContent="Manage Regulations",s.innerHTML=Cs(t)}}[e]||(()=>{s.innerHTML=""}))(),Oe()}async function xs(){try{se=await h("/jurisdictions"),Bt()}catch(e){document.getElementById("jurisdictions-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function Bt(){const e=document.getElementById("jurisdictions-table");if(!se.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No jurisdictions defined yet.</td></tr>';return}e.innerHTML=se.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.jurisdictionName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("jurisdiction-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteJurisdiction("${r(t.jurisdictionName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Ct(e){const t=e?r(e.jurisdictionName):"",n=e?r(e.description||""):"";return`
        <form onsubmit="saveJurisdiction(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Name</label>
                    <input id="pf-jur-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-jur-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-jur-msg" class="hidden"></span>
        </form>`}async function hs(e,t,n){e.preventDefault();const s=document.getElementById("pf-jur-name").value.trim(),a=document.getElementById("pf-jur-desc").value.trim(),o=document.getElementById("pf-jur-msg");try{t?await h(`/jurisdictions/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await h("/jurisdictions",{method:"POST",body:JSON.stringify({jurisdictionName:s,description:a})}),w(),await kt()}catch(i){L(o,i.message,!0)}}function vs(e){H(`Delete jurisdiction "${e}"? This cannot be undone.`,async()=>{try{await h(`/jurisdictions/${encodeURIComponent(e)}`,{method:"DELETE"}),await kt()}catch(t){alert(t.message)}})}async function ws(){try{G=await h("/regulations"),Mt()}catch(e){document.getElementById("regulations-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function Mt(){const e=document.getElementById("regulations-table");if(!G.length){e.innerHTML='<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">No regulations defined yet.</td></tr>';return}e.innerHTML=G.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.regulationName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.activeJurisdictions||[]).map(n=>de(n.jurisdictionName,"teal")).join("")}
                    <button onclick='openPanel("regulation-jurisdictions", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded-md text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteRegulation("${r(t.regulationName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Es(){return`
        <form onsubmit="saveRegulation(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Name</label>
                    <input id="pf-reg-name" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-reg-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-reg-msg" class="hidden"></span>
        </form>`}async function Ns(e){e.preventDefault();const t=document.getElementById("pf-reg-name").value.trim(),n=document.getElementById("pf-reg-desc").value.trim(),s=document.getElementById("pf-reg-msg");try{await h("/regulations",{method:"POST",body:JSON.stringify({regulationName:t,description:n})}),w(),await Te()}catch(a){L(s,a.message,!0)}}function $s(e){H(`Delete regulation "${e}"? This cannot be undone.`,async()=>{try{await h(`/regulations/${encodeURIComponent(e)}`,{method:"DELETE"}),await Te()}catch(t){alert(t.message)}})}function Ss(e){const t=new Set((e.activeJurisdictions||[]).map(s=>s.jurisdictionName)),n=se.filter(s=>!t.has(s.jurisdictionName)).map(s=>`<option value="${r(s.jurisdictionName)}">${r(s.jurisdictionName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage jurisdictions for <strong class="text-gray-900">${r(e.regulationName)}</strong></p>
        <div id="rj-list" class="space-y-2 mb-6">
            ${(e.activeJurisdictions||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <span class="text-sm text-gray-900">${r(s.jurisdictionName)}</span>
                    <button onclick="removeJurisdictionFromRegulation('${r(e.regulationName)}','${r(s.jurisdictionName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No jurisdictions linked.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Add a jurisdiction</p>
        <form onsubmit="addJurisdictionToRegulation(event, '${r(e.regulationName)}')">
            <div class="flex gap-2">
                <select id="rj-select" class="flex-1 bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${n}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Add</button>
            </div>
            <span id="rj-msg" class="hidden"></span>
        </form>`}async function js(e,t){e.preventDefault();const s=document.getElementById("rj-select").value;if(s)try{await h("/regulations/jurisdictions",{method:"POST",body:JSON.stringify({regulationName:t,jurisdictionName:s})}),w(),await Te();const a=G.find(o=>o.regulationName===t);a&&ye("regulation-jurisdictions",a)}catch(a){L(document.getElementById("rj-msg"),a.message,!0)}}async function Ts(e,t){try{await h(`/regulations/${encodeURIComponent(e)}/jurisdictions/${encodeURIComponent(t)}`,{method:"DELETE"}),w(),await Te();const n=G.find(s=>s.regulationName===e);n&&ye("regulation-jurisdictions",n)}catch(n){alert(n.message)}}async function Is(){try{A=await h("/purposes"),Ot(),At()}catch(e){document.getElementById("purposes-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${r(e.message)}</td></tr>`}}function Ot(){const e=document.getElementById("purposes-table");if(!A.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No purposes defined yet.</td></tr>';return}e.innerHTML=A.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(t.purposeName)}</td>
            <td class="px-6 py-4 text-gray-400">${r(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.regulations||[]).map(n=>de(n.regulationName,"blue")).join("")}
                    <button onclick='openPanel("purpose-regulations", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded-md text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.policies||[]).map(n=>de(n.policyType==="FIELD"?n.field?n.field.entityName+"/"+n.field.fieldName:"field":n.category?n.category.categoryName:"category","purple")).join("")}
                    <span class="text-xs text-gray-500">${(t.policies||[]).length}</span>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deletePurpose("${r(t.purposeName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Ls(){return`
        <form onsubmit="savePurpose(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Name</label>
                    <input id="pf-pur-name" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Description</label>
                    <textarea id="pf-pur-desc" rows="3"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-pur-msg" class="hidden"></span>
        </form>`}async function ks(e){e.preventDefault();const t=document.getElementById("pf-pur-name").value.trim(),n=document.getElementById("pf-pur-desc").value.trim(),s=document.getElementById("pf-pur-msg");try{await h("/purposes",{method:"POST",body:JSON.stringify({purposeName:t,description:n})}),w(),await ae()}catch(a){L(s,a.message,!0)}}function Bs(e){H(`Delete purpose "${e}"? This will also remove all linked policies and regulations.`,async()=>{try{await h(`/purposes/${encodeURIComponent(e)}`,{method:"DELETE"}),await ae()}catch(t){alert(t.message)}})}function Cs(e){const t=new Set((e.regulations||[]).map(s=>s.regulationName)),n=G.filter(s=>!t.has(s.regulationName)).map(s=>`<option value="${r(s.regulationName)}">${r(s.regulationName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage regulation links for <strong class="text-gray-900">${r(e.purposeName)}</strong></p>
        <div id="pr-list" class="space-y-2 mb-6">
            ${(e.regulations||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <div>
                        <span class="text-sm text-gray-900">${r(s.regulationName)}</span>
                        ${s.lawfulBasis?`<span class="ml-2 text-xs text-gray-500">basis: ${r(s.lawfulBasis)}</span>`:""}
                    </div>
                    <button onclick="unlinkPurposeRegulation('${r(e.purposeName)}','${r(s.regulationName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Unlink</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No regulations linked.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Link a regulation</p>
        <form onsubmit="linkPurposeRegulation(event, '${r(e.purposeName)}')">
            <div class="space-y-3">
                <select id="pr-select" class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                    <option value="">Select regulation…</option>
                    ${n}
                </select>
                <input id="pr-basis" placeholder="Lawful basis (e.g. consent, legitimate_interest)"
                    class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                <div class="grid grid-cols-2 gap-3">
                    <input id="pr-granted" placeholder="On Granted action"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                    <input id="pr-denied" placeholder="On Denied action"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none" />
                </div>
                <button type="submit" class="w-full px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Link</button>
            </div>
            <span id="pr-msg" class="hidden"></span>
        </form>`}async function Ms(e,t){e.preventDefault();const n=document.getElementById("pr-select").value;if(!n)return;const s=document.getElementById("pr-basis").value.trim()||null,a=document.getElementById("pr-granted").value.trim()||null,o=document.getElementById("pr-denied").value.trim()||null;try{await h("/purposes/regulations",{method:"POST",body:JSON.stringify({purposeName:t,regulationName:n,lawfulBasis:s,onGranted:a,onDenied:o})}),w(),await ae();const i=A.find(l=>l.purposeName===t);i&&ye("purpose-regulations",i)}catch(i){L(document.getElementById("pr-msg"),i.message,!0)}}async function Os(e,t){try{await h(`/purposes/${encodeURIComponent(e)}/regulations/${encodeURIComponent(t)}`,{method:"DELETE"}),w(),await ae();const n=A.find(s=>s.purposeName===e);n&&ye("purpose-regulations",n)}catch(n){alert(n.message)}}function At(){const e=document.getElementById("policies-table"),t=[];if(A.forEach(n=>{(n.policies||[]).forEach(s=>{t.push({...s,purposeName:n.purposeName})})}),!t.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No policies defined yet. Add policies from the Purposes tab.</td></tr>';return}e.innerHTML=t.map(n=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${r(n.purposeName)}</td>
            <td class="px-6 py-4">${de(n.policyType,n.policyType==="FIELD"?"teal":"purple")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"?r(n.field?n.field.fieldName:"—"):r(n.category?n.category.categoryName:"—")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"&&n.field?r(n.field.entityName):"—"}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='removePolicy("${r(n.purposeName)}", ${JSON.stringify(n).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Remove</button>
            </td>
        </tr>`).join("")}function As(){const e=Ke.map(n=>{const s=(n.entityName?n.entityName+"/":"")+n.fieldName;return`<option value="${JSON.stringify({fieldName:n.fieldName,entityName:n.entityName||""}).replace(/"/g,"&quot;")}">${r(s)}</option>`}).join(""),t=Xe.map(n=>`<option value="${r(n.categoryName)}">${r(n.categoryName)}</option>`).join("");return`
        <form onsubmit="addPolicy(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Purpose</label>
                    <select id="pf-pol-purpose" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select purpose…</option>
                        ${A.map(n=>`<option value="${r(n.purposeName)}">${r(n.purposeName)}</option>`).join("")}
                    </select>
                </div>
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Policy Type</label>
                    <select id="pf-pol-type" onchange="togglePolicyFields()" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="FIELD">Field</option>
                        <option value="CATEGORY">Category</option>
                    </select>
                </div>
                <div id="pf-pol-field-group">
                    <label class="block text-sm text-gray-500 mb-1">Field</label>
                    <select id="pf-pol-field-select" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select field…</option>
                        ${e}
                    </select>
                    ${Ke.length?"":'<p class="text-xs text-amber-400 mt-1">No fields registered. Add fields in the Catalogue first.</p>'}
                </div>
                <div id="pf-pol-cat-group" class="hidden">
                    <label class="block text-sm text-gray-500 mb-1">Category</label>
                    <select id="pf-pol-cat-select"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select category…</option>
                        ${t}
                    </select>
                    ${Xe.length?"":'<p class="text-xs text-amber-400 mt-1">No categories registered. Add categories in the Catalogue first.</p>'}
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Add Policy</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-pol-msg" class="hidden"></span>
        </form>`}function Ds(){const e=document.getElementById("pf-pol-type").value;document.getElementById("pf-pol-field-group").classList.toggle("hidden",e!=="FIELD"),document.getElementById("pf-pol-cat-group").classList.toggle("hidden",e!=="CATEGORY"),document.getElementById("pf-pol-field-select").required=e==="FIELD",document.getElementById("pf-pol-cat-select").required=e==="CATEGORY"}async function Rs(e){e.preventDefault();const t=document.getElementById("pf-pol-purpose").value,n=document.getElementById("pf-pol-type").value,s=document.getElementById("pf-pol-msg"),a={purposeName:t,policyType:n};if(n==="FIELD"){const o=document.getElementById("pf-pol-field-select").value;if(!o){L(s,"Please select a field.",!0);return}const i=JSON.parse(o);a.fieldName=i.fieldName,a.entityName=i.entityName,a.categoryName=null}else{if(a.categoryName=document.getElementById("pf-pol-cat-select").value,!a.categoryName){L(s,"Please select a category.",!0);return}a.fieldName=null,a.entityName=null}try{await h("/purposes/policies",{method:"POST",body:JSON.stringify(a)}),w(),await ae()}catch(o){L(s,o.message,!0)}}async function Ps(e,t){H(`Remove this ${t.policyType.toLowerCase()} policy from "${e}"?`,async()=>{try{const n={purposeName:e,policyType:t.policyType,fieldName:t.field?t.field.fieldName:null,entityName:t.field?t.field.entityName:null,categoryName:t.category?t.category.categoryName:null};await h("/purposes/policies",{method:"DELETE",body:JSON.stringify(n)}),await ae()}catch(n){alert(n.message)}})}function _s(){Object.assign(window,{showTab:ce,openPanel:ye,closePanel:w,closeDeleteModal:ve,saveJurisdiction:hs,deleteJurisdiction:vs,saveRegulation:Ns,deleteRegulation:$s,addJurisdictionToRegulation:js,removeJurisdictionFromRegulation:Ts,savePurpose:ks,deletePurpose:Bs,linkPurposeRegulation:Ms,unlinkPurposeRegulation:Os,togglePolicyFields:Ds,addPolicy:Rs,removePolicy:Ps}),Me(),Ae(()=>{xs(),ws(),Is(),bs();const e=window.location.hash.replace("#","");["jurisdictions","regulations","purposes","policies"].includes(e)&&ce(e)})}const Hs=Object.freeze(Object.defineProperty({__proto__:null,init:_s},Symbol.toStringTag,{value:"Module"}));return f.billing=ln,f.catalogue=Sn,f.consent=fs,f.governance=Hs,Object.defineProperty(f,Symbol.toStringTag,{value:"Module"}),f})({});
