var CveilWeb=(function(f){"use strict";var xt=f=>{throw TypeError(f)};var bt=(f,o,h)=>o.has(f)||xt("Cannot "+h);var T=(f,o,h)=>(bt(f,o,"read from private field"),h?h.call(f):o.get(f)),ht=(f,o,h)=>o.has(f)?xt("Cannot add the same private member more than once"):o instanceof WeakSet?o.add(f):o.set(f,h),vt=(f,o,h,O)=>(bt(f,o,"write to private field"),O?O.call(f,h):o.set(f,h),h);var $;function o(e){const t=document.createElement("div");return t.textContent=e,t.innerHTML}function h(e){document.querySelectorAll('[id^="section-"]').forEach(n=>n.classList.add("hidden")),document.querySelectorAll(".tab-btn").forEach(n=>{n.classList.remove("border-cv-500","text-cv-500"),n.classList.add("border-transparent","text-gray-500")}),document.getElementById("section-"+e).classList.remove("hidden");const t=document.getElementById("tab-"+e);t.classList.add("border-cv-500","text-cv-500"),t.classList.remove("border-transparent","text-gray-400"),window.location.hash=e}function O(e){return async function(n,s={}){const a={...s.headers};s.body&&(a["Content-Type"]="application/json");const r=await fetch(e+n,{...s,headers:a});if(!r.ok){const c=await r.json().catch(()=>({message:r.statusText}));throw new Error(c.message||r.statusText)}return r.status===204?null:r.json()}}let oe=null;function M(e,t){document.getElementById("delete-modal-msg").textContent=e,document.getElementById("delete-modal").classList.remove("hidden"),oe=t}function re(){document.getElementById("delete-modal").classList.add("hidden"),oe=null}function me(){document.getElementById("delete-modal-confirm").addEventListener("click",async()=>{oe&&(await oe(),re())})}function ye(){const e=document.getElementById("panel-overlay"),t=document.getElementById("panel");e.classList.remove("hidden"),requestAnimationFrame(()=>t.classList.remove("translate-x-full"))}function v(){document.getElementById("panel").classList.add("translate-x-full"),setTimeout(()=>document.getElementById("panel-overlay").classList.add("hidden"),300)}function Q(e,t="gray"){const n={gray:"bg-gray-100 text-gray-700",teal:"bg-cv-50 text-cv-700 border border-cv-200",blue:"bg-blue-50 text-blue-700 border border-blue-200",purple:"bg-purple-50 text-purple-700 border border-purple-200"};return`<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${n[t]||n.gray}">${o(e)}</span>`}function I(e,t,n=!1){e.textContent=t,e.className="text-sm mt-3 "+(n?"text-red-400":"text-green-400"),e.classList.remove("hidden"),setTimeout(()=>e.classList.add("hidden"),4e3)}function Z(e){return e?new Date(e).toLocaleString(void 0,{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"}):"—"}function fe(e){document.readyState!=="loading"?e():document.addEventListener("DOMContentLoaded",e)}const wt={maxAttempts:5,initialDelay:60,maxDelay:500,backoff:2};async function J(e,t,n={}){const{maxAttempts:s,initialDelay:a,maxDelay:r,backoff:c}={...wt,...n},l=JSON.stringify(t);let u=a;for(let m=0;m<s;m++){await new Promise(y=>setTimeout(y,u));try{const y=await e();if(JSON.stringify(y)!==l)return y}catch{}u=Math.min(u*c,r)}try{return await e()}catch{return t}}function w(e,t){const n=document.getElementById(e);n&&(n.style.opacity=t?"0.45":"",n.style.pointerEvents=t?"none":"",n.style.transition="opacity 0.15s ease")}const E=O("/api/v1/catalogue");let H=[],_=[],G=[];async function xe(){w("entities-table",!0),H=await J(()=>E("/entities"),H),Ae(),w("entities-table",!1)}async function ie(){w("fields-table",!0),_=await J(()=>E("/fields"),_),Re(),w("fields-table",!1)}async function Me(){w("categories-table",!0),G=await J(()=>E("/categories"),G),Pe(),w("categories-table",!1)}function be(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"entity-add":()=>{n.textContent="Add Entity",s.innerHTML=De()},"entity-edit":()=>{n.textContent="Edit Entity",s.innerHTML=De(t)},"field-add":()=>{n.textContent="Add Field",s.innerHTML=jt()},"field-categories":()=>{n.textContent="Manage Categories",s.innerHTML=Bt(t)},"category-add":()=>{n.textContent="Add Category",s.innerHTML=Je()},"category-edit":()=>{n.textContent="Edit Category",s.innerHTML=Je(t)}}[e]||(()=>{s.innerHTML=""}))(),ye()}async function Et(){try{H=await E("/entities"),Ae()}catch(e){document.getElementById("entities-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function Ae(){const e=document.getElementById("entities-table");if(!H.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No entities defined yet.</td></tr>';return}e.innerHTML=H.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.entityName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("entity-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteEntity("${o(t.entityName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function De(e){const t=e?o(e.entityName):"",n=e?o(e.description||""):"";return`
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
        </form>`}async function Nt(e,t,n){e.preventDefault();const s=document.getElementById("pf-ent-name").value.trim(),a=document.getElementById("pf-ent-desc").value.trim(),r=document.getElementById("pf-ent-msg");try{t?await E(`/entities/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await E("/entities",{method:"POST",body:JSON.stringify({entityName:s,description:a})}),v(),await xe()}catch(c){I(r,c.message,!0)}}function $t(e){M(`Delete entity "${e}"? This cannot be undone. Entities with fields must have their fields removed first.`,async()=>{try{await E(`/entities/${encodeURIComponent(e)}`,{method:"DELETE"}),await xe()}catch(t){alert(t.message)}})}async function St(){try{_=await E("/fields"),Re()}catch(e){document.getElementById("fields-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function Re(){const e=document.getElementById("fields-table");if(!_.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No fields defined yet.</td></tr>';return}e.innerHTML=_.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.fieldName)}</td>
            <td class="px-6 py-4 text-gray-400">${t.entity?o(t.entity.entityName):'<span class="text-gray-600">—</span>'}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.categories||[]).map(n=>Q(n.categoryName,"purple")).join("")}
                    <button onclick='openPanel("field-categories", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteField("${t.entity?o(t.entity.entityName):""}", "${o(t.fieldName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function jt(){return`
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
                        ${H.map(t=>`<option value="${o(t.entityName)}">${o(t.entityName)}</option>`).join("")}
                    </select>
                    ${H.length?"":'<p class="text-xs text-amber-400 mt-1">No entities registered. Add an entity first.</p>'}
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
        </form>`}async function Tt(e){e.preventDefault();const t=document.getElementById("pf-fld-name").value.trim(),n=document.getElementById("pf-fld-entity").value,s=document.getElementById("pf-fld-desc").value.trim()||null,a=document.getElementById("pf-fld-ent-desc").value.trim()||null,r=document.getElementById("pf-fld-msg");try{await E("/fields",{method:"POST",body:JSON.stringify({fieldName:t,entityName:n,description:s,entityDescription:a})}),v(),await ie(),await xe()}catch(c){I(r,c.message,!0)}}function It(e,t){M(`Delete field "${t}" from entity "${e}"? This cannot be undone.`,async()=>{try{await E(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}`,{method:"DELETE"}),await ie()}catch(n){alert(n.message)}})}async function Lt(){try{G=await E("/categories"),Pe()}catch(e){document.getElementById("categories-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function Bt(e){const t=e.entity?e.entity.entityName:"",n=new Set((e.categories||[]).map(a=>a.categoryName)),s=G.filter(a=>!n.has(a.categoryName)).map(a=>`<option value="${o(a.categoryName)}">${o(a.categoryName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage categories for field <strong class="text-gray-900">${o(e.fieldName)}</strong> on entity <strong class="text-gray-900">${o(t)}</strong></p>
        <div id="fc-list" class="space-y-2 mb-6">
            ${(e.categories||[]).map(a=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <span class="text-sm text-gray-900">${o(a.categoryName)}</span>
                    <button onclick="unassignFieldCategory('${o(t)}','${o(e.fieldName)}','${o(a.categoryName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No categories assigned.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Assign a category</p>
        <form onsubmit="assignFieldCategory(event, '${o(t)}', '${o(e.fieldName)}')">
            <div class="flex gap-2">
                <select id="fc-select" class="flex-1 bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${s}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Assign</button>
            </div>
            <span id="fc-msg" class="hidden"></span>
        </form>`}async function kt(e,t,n){e.preventDefault();const s=document.getElementById("fc-select").value;if(s)try{await E("/fields/categories",{method:"POST",body:JSON.stringify({fieldName:n,entityName:t,categoryName:s})}),v(),await ie();const a=_.find(r=>r.fieldName===n&&r.entity&&r.entity.entityName===t);a&&be("field-categories",a)}catch(a){I(document.getElementById("fc-msg"),a.message,!0)}}async function Ct(e,t,n){try{await E(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}/categories/${encodeURIComponent(n)}`,{method:"DELETE"}),v(),await ie();const s=_.find(a=>a.fieldName===t&&a.entity&&a.entity.entityName===e);s&&be("field-categories",s)}catch(s){alert(s.message)}}function Pe(){const e=document.getElementById("categories-table");if(!G.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No categories defined yet.</td></tr>';return}e.innerHTML=G.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.categoryName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("category-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteCategory("${o(t.categoryName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Je(e){const t=e?o(e.categoryName):"",n=e?o(e.description||""):"";return`
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
        </form>`}async function Ot(e,t,n){e.preventDefault();const s=document.getElementById("pf-cat-name").value.trim(),a=document.getElementById("pf-cat-desc").value.trim(),r=document.getElementById("pf-cat-msg");try{t?await E(`/categories/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await E("/categories",{method:"POST",body:JSON.stringify({categoryName:s,description:a})}),v(),await Me()}catch(c){I(r,c.message,!0)}}function Mt(e){M(`Delete category "${e}"? This cannot be undone. Categories still assigned to fields must be unassigned first.`,async()=>{try{await E(`/categories/${encodeURIComponent(e)}`,{method:"DELETE"}),await Me()}catch(t){alert(t.message)}})}function At(){Object.assign(window,{showTab:h,openPanel:be,closePanel:v,closeDeleteModal:re,saveEntity:Nt,deleteEntity:$t,saveField:Tt,deleteField:It,assignFieldCategory:kt,unassignFieldCategory:Ct,saveCategory:Ot,deleteCategory:Mt}),me(),fe(()=>{Et(),St(),Lt();const e=window.location.hash.replace("#","");["entities","fields","categories"].includes(e)&&h(e)})}const Dt=Object.freeze(Object.defineProperty({__proto__:null,init:At},Symbol.toStringTag,{value:"Module"})),L=Object.freeze({Plaintext:"Plaintext",RedactAll:"RedactAll",RedactUpToLast4:"RedactUpToLast4",Hash256:"Hash256",RedactLocal:"RedactLocal"}),Rt=Object.values(L);function ce(e){return typeof e=="string"?"***":typeof e=="number"?(Number.isInteger(e),0):typeof e=="boolean"?!1:null}function Pt(e){if(typeof e=="string"){const t=[...e];if(t.length<=4)return"****";const n=t.length-4;return"*".repeat(n)+t.slice(n).join("")}return ce(e)}async function Jt(e){if(typeof e=="string"||typeof e=="number"){const t=String(e),n=new TextEncoder().encode(t),s=await crypto.subtle.digest("SHA-256",n);return Array.from(new Uint8Array(s)).map(a=>a.toString(16).padStart(2,"0")).join("")}return ce(e)}function Ht(e){if(typeof e=="string"){const t=e.indexOf("@");if(t>=0){const n=e.slice(t),s=Math.max(t,4);return"*".repeat(s)+n}}return ce(e)}async function He(e,t){switch(t){case L.Plaintext:return e;case L.RedactAll:return ce(e);case L.RedactUpToLast4:return Pt(e);case L.Hash256:return Jt(e);case L.RedactLocal:return Ht(e);default:return e}}async function _t(e,t){return he(structuredClone(e),"",t)}async function he(e,t,n){if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const s of Object.keys(e)){const a=t?`${t}.${s}`:s,r=n[a];if(r&&r!==L.Plaintext){e[s]=await He(e[s],r);continue}e[s]=await he(e[s],a,n)}else if(Array.isArray(e))for(let s=0;s<e.length;s++)e[s]=await he(e[s],t,n);else if(t){const s=n[t];if(s&&s!==L.Plaintext)return He(e,s)}return e}function _e(e,t=""){const n=[];if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const[s,a]of Object.entries(e)){const r=t?`${t}.${s}`:s;a!==null&&typeof a=="object"&&!Array.isArray(a)?n.push(..._e(a,r)):n.push(r)}return n}const Ft=new TextEncoder,Ut=new TextDecoder;class ee extends Error{constructor(t){super(t),this.name="CveilWasmError"}}const Oe=class Oe{constructor(){ht(this,$,null)}static async load(t){const n=new Oe,s={env:{cveil_host_time_ms:()=>BigInt(Date.now())}};let a;if(typeof WebAssembly.instantiateStreaming=="function")a=(await WebAssembly.instantiateStreaming(fetch(t),s)).instance.exports;else{const c=await(await fetch(t)).arrayBuffer(),l=await WebAssembly.compile(c);a=(await WebAssembly.instantiate(l,s)).exports}return vt(n,$,a),n}get exports(){return T(this,$)}writeString(t){const n=Ft.encode(t),s=T(this,$).cveil_alloc(n.length);return new Uint8Array(T(this,$).memory.buffer,s,n.length).set(n),[s,n.length]}readResult(){const t=T(this,$).cveil_result_ptr(),n=T(this,$).cveil_result_len();if(!t||!n)return"";const s=new Uint8Array(T(this,$).memory.buffer,t,n);return Ut.decode(s)}freeString(t,n){T(this,$).cveil_dealloc(t,n)}call0(t){const n=t(),s=this.readResult();if(T(this,$).cveil_free_result(),n!==0)throw new ee(s||"WASM call failed");return s}call1(t,n){const[s,a]=this.writeString(n);try{const r=t(s,a),c=this.readResult();if(T(this,$).cveil_free_result(),r!==0)throw new ee(c||"WASM call failed");return c}finally{this.freeString(s,a)}}call2(t,n,s){const[a,r]=this.writeString(n),[c,l]=this.writeString(s);try{const u=t(a,r,c,l),m=this.readResult();if(T(this,$).cveil_free_result(),u!==0)throw new ee(m||"WASM call failed");return m}finally{this.freeString(a,r),this.freeString(c,l)}}call4(t,n,s,a,r){const[c,l]=this.writeString(n),[u,m]=this.writeString(s),[y,p]=this.writeString(a),[i,g]=this.writeString(r);try{const b=t(c,l,u,m,y,p,i,g),d=this.readResult();if(T(this,$).cveil_free_result(),b!==0)throw new ee(d||"WASM call failed");return d}finally{this.freeString(c,l),this.freeString(u,m),this.freeString(y,p),this.freeString(i,g)}}call5(t,n,s,a,r,c){const[l,u]=this.writeString(n),[m,y]=this.writeString(s),[p,i]=this.writeString(a),[g,b]=this.writeString(r),[d,j]=this.writeString(c);try{const C=t(l,u,m,y,p,i,g,b,d,j),X=this.readResult();if(T(this,$).cveil_free_result(),C!==0)throw new ee(X||"WASM call failed");return X}finally{this.freeString(l,u),this.freeString(m,y),this.freeString(p,i),this.freeString(g,b),this.freeString(d,j)}}};$=new WeakMap;let ve=Oe;const we=O("/api/v1/catalogue"),Gt=O("/api/v1/governance"),Ee=O("/api/v1/subjects");let Fe=[],Ue=[],Wt=[],Ge=[],A=[],F={},U=null,W=null,We=!1,B=null,le=!1;const qt="/wasm/cveil_sdk_core.wasm",qe={User:{name:"Alice Smith",email:"alice@example.com",ssn:"123-45-6789",age:30,address:{street:"123 Main St",city:"Springfield",zip:"62704"}},Patient:{patientId:"P-78291",firstName:"John",lastName:"Doe",dateOfBirth:"1985-03-14",diagnosis:"Hypertension",insurance:{provider:"BlueCross",policyNumber:"BC-44921-X"}},Order:{orderId:"ORD-10042",customerEmail:"jane@shop.com",phone:"+1-555-867-5309",total:149.99,items:[{sku:"WIDGET-A",name:"Widget Alpha",qty:2}],shipping:{street:"456 Oak Ave",city:"Portland",state:"OR"}}};async function zt(){var e;if(!le)try{B=await ve.load(qt);const t=B.exports;B.call1(t.cveil_init,JSON.stringify({api_base_url:window.location.origin,api_token:"",default_jurisdiction:""}));let n=null,s=!0;for(;s;){const a=n?`/api/v1/consent/graph?limit=500&cursor=${encodeURIComponent(n)}`:"/api/v1/consent/graph?limit=500",r=await fetch(a);if(!r.ok)throw new Error("Graph fetch failed");const c=await r.json();s=JSON.parse(B.call1(t.cveil_ingest_graph_page,JSON.stringify(c))).hasMore,n=((e=c.pageInfo)==null?void 0:e.nextCursor)||null,n||(s=!1)}le=!0,Ye(!0),N("WASM engine loaded — using real masking SDK",!1),setTimeout(()=>N("",!1),3e3)}catch(t){console.warn("WASM init failed, falling back to JS masking:",t),Ye(!1),N("WASM unavailable — using JS masking fallback",!1),setTimeout(()=>N("",!1),4e3)}}async function Kt(){if(!We){We=!0;try{const[e,t,n,s]=await Promise.all([we("/entities"),Gt("/purposes"),we("/categories"),Ee(""),zt()]);Fe=e||[],Ue=t||[],Wt=n||[],Ge=Array.isArray(s)?s:(s==null?void 0:s.items)||[],Ne("pg-subject",Ge.map(a=>a.name||a.subjectName||a)),Ne("pg-entity",Fe.map(a=>a.name||a.entityName||a)),Ne("pg-purpose",Ue.map(a=>a.name||a.purposeName||a)),Yt()}catch(e){N(`Failed to load reference data: ${e.message}`,!0)}}}function Ne(e,t){const n=document.getElementById(e);if(!n)return;const s=n.value;n.innerHTML='<option value="">— select —</option>'+t.map(a=>`<option value="${o(String(a))}">${o(String(a))}</option>`).join(""),s&&t.includes(s)&&(n.value=s)}function Yt(){const e=document.getElementById("pg-templates");e&&(e.innerHTML=Object.keys(qe).map(t=>`<button onclick="loadSampleTemplate('${t}')"
            class="px-2 py-1 text-xs border border-gray-300 rounded-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">${o(t)}</button>`).join(""))}function Vt(e){const t=qe[e];if(!t)return;const n=document.getElementById("pg-json-input");n&&(n.value=JSON.stringify(t,null,2)),sn()}async function Xt(){var l,u,m,y,p;const e=(l=document.getElementById("pg-subject"))==null?void 0:l.value,t=(u=document.getElementById("pg-entity"))==null?void 0:u.value,n=(m=document.getElementById("pg-purpose"))==null?void 0:m.value,s=(p=(y=document.getElementById("pg-json-input"))==null?void 0:y.value)==null?void 0:p.trim();if(!e)return N("Select a subject.",!0);if(!t)return N("Select an entity.",!0);if(!n)return N("Select a purpose.",!0);if(!s)return N("Enter JSON data.",!0);let a;try{a=JSON.parse(s)}catch(i){return N(`Invalid JSON: ${i.message}`,!0)}U=a;const r=_e(a);if(r.length===0)return N("No leaf fields found in JSON.",!0);N("Evaluating consent…",!1);const c=document.getElementById("pg-evaluate-btn");c&&(c.disabled=!0);try{le?await Qt(e,t,n,a,r):await en(e,t,n,a,r),N("",!1)}catch(i){N(`Evaluate failed: ${i.message}`,!0)}finally{c&&(c.disabled=!1)}}async function Qt(e,t,n,s,a){const r=B.exports;let c=B.call4(r.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(c);if(l.needsFetch&&l.needsFetch.length>0&&[...new Set(l.needsFetch.flatMap(p=>p.policyNames||[]))].length>0){const p=a.map(i=>({fieldName:i.split(".").pop(),entityName:t,purposeName:n}));try{const i=await Ee(`/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:p})}),g=Zt(i);g.length>0&&B.call2(r.cveil_ingest_decisions,e,JSON.stringify(g)),c=B.call4(r.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(c)}catch(i){console.warn("Decision fetch failed, using deny-by-default masking:",i)}}W=l.data;const u=B.call4(r.cveil_evaluate,e,t,JSON.stringify(a),n),m=JSON.parse(u);A=a.map((y,p)=>{var d;const i=m[p]||{},g=i.masking_type||i.maskingType||L.RedactAll,b=i.needs_fetch||i.needsFetch||!1;return{path:y,fieldName:i.field||y.split(".").pop(),status:g===L.Plaintext?"GRANTED":"DENIED",resolvedBy:b?"PENDING":(d=i.policy_names)!=null&&d.length?"POLICY":"GRAPH",maskingType:g,detail:i}}),F={},Ke(),$e(l.data)}function Zt(e){const t=[],n=e.details||e.reasons||{},s=new Set((e.granted||[]).map(a=>a.fieldName||a));for(const[a,r]of Object.entries(n))(r.policyName||r.policy_name)&&t.push({policyName:r.policyName||r.policy_name,action:s.has(a)?"granted":"denied",expiresAt:r.expiresAt||r.expires_at||null});return t}async function en(e,t,n,s,a){let r=[];try{r=await we(`/entities/${encodeURIComponent(t)}/fields`)||[]}catch{}const c={};for(const p of r)c[p.name||p.fieldName]=p.categoryName||p.category||"";const l=a.map(p=>{const i=p.split(".").pop();return{fieldName:i,entityName:t,purposeName:n,category:c[i]||""}}),u=await Ee(`/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:l})}),m=new Set((u.granted||[]).map(p=>p.fieldName||p)),y=u.details||u.reasons||{};A=a.map(p=>{const i=p.split(".").pop(),g=m.has(i),b=y[i]||y[p]||{},d=b.maskingType||(g?L.Plaintext:L.RedactAll);return{path:p,fieldName:i,status:g?"GRANTED":"DENIED",resolvedBy:b.resolvedBy||"NONE",maskingType:d,detail:b}}),F={},Ke(),await ze()}async function ze(){if(!U||A.length===0)return;const e={};for(const n of A)e[n.path]=F[n.path]||n.maskingType;const t=await _t(U,e);W=t,$e(t)}async function tn(){var t,n,s;for(const a of A){const r=document.getElementById(`pg-override-${CSS.escape(a.path)}`);r&&r.value&&(F[a.path]=r.value)}const e=Object.keys(F).length>0;if(le&&!e){const a=(t=document.getElementById("pg-subject"))==null?void 0:t.value,r=(n=document.getElementById("pg-entity"))==null?void 0:n.value,c=(s=document.getElementById("pg-purpose"))==null?void 0:s.value;if(a&&r&&c&&U)try{const l=B.exports,u=B.call4(l.cveil_mask,a,r,JSON.stringify(U),c),m=JSON.parse(u);W=m.data,$e(m.data);return}catch{}}await ze()}function Ke(){const e=document.getElementById("pg-decisions");if(!e||A.length===0)return;const t=A.map(n=>{const s=F[n.path]||n.maskingType,a=n.status==="GRANTED"?'<span class="inline-flex items-center gap-1 text-xs font-medium text-green-400"><span class="w-1.5 h-1.5 rounded-full bg-green-500"></span>GRANTED</span>':'<span class="inline-flex items-center gap-1 text-xs font-medium text-red-400"><span class="w-1.5 h-1.5 rounded-full bg-red-500"></span>DENIED</span>',r=Rt.map(c=>`<option value="${c}" ${c===s?"selected":""}>${c}</option>`).join("");return`<tr class="border-t border-gray-200">
            <td class="px-3 py-2 font-mono text-xs text-gray-600">${o(n.path)}</td>
            <td class="px-3 py-2">${a}</td>
            <td class="px-3 py-2 text-xs text-gray-500">${o(n.resolvedBy)}</td>
            <td class="px-3 py-2">
                <select id="pg-override-${CSS.escape(n.path)}" onchange="remask()"
                    class="bg-white border border-gray-300 rounded px-2 py-1 text-xs text-gray-700 focus:border-cv-500 focus:outline-none">
                    ${r}
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
    </table>`}function $e(e){const t=document.getElementById("pg-masked-output");if(!t)return;const n=JSON.stringify(e,null,2),a=JSON.stringify(U,null,2).split(`
`),r=n.split(`
`);let c="";for(let l=0;l<r.length;l++){const u=l<a.length?a[l]:"",m=r[l];u!==m?c+=`<div class="bg-red-900/20 text-red-300">${o(m)}</div>`:c+=`<div class="text-gray-300">${o(m)}</div>`}t.innerHTML=`<div class="flex items-center justify-between mb-2">
        <span class="text-xs text-gray-500 uppercase tracking-wide">Masked Output</span>
        <button onclick="copyMaskedOutput()" class="text-xs text-cv-500 hover:text-cv-400 transition-colors">Copy</button>
    </div>
    <pre class="font-mono text-xs leading-relaxed overflow-auto" style="max-height:340px">${c}</pre>`}function nn(){W&&(navigator.clipboard.writeText(JSON.stringify(W,null,2)),N("Copied to clipboard!",!1),setTimeout(()=>N("",!1),2e3))}function sn(){A=[],F={},U=null,W=null;const e=document.getElementById("pg-decisions"),t=document.getElementById("pg-masked-output");e&&(e.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Run an evaluation to see consent decisions.</p>'),t&&(t.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Masked output will appear here.</p>')}function Ye(e){const t=document.getElementById("pg-wasm-badge");t&&(e?(t.textContent="WASM SDK",t.className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700"):(t.textContent="JS Fallback",t.className="text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700"))}function N(e,t){const n=document.getElementById("playground-msg");if(n){if(!e){n.className="hidden",n.textContent="";return}n.className=`text-xs px-3 py-2 rounded ${t?"bg-red-50 text-red-600":"bg-gray-100 text-gray-500"}`,n.textContent=e}}const Ve="/api/v1/consent/graph",Xe={purpose:"#14b8a6",regulation:"#3b82f6",jurisdiction:"#f59e0b",field:"#a855f7",subject:"#ec4899"};let Qe=!1,Ze="force",S={nodes:[],edges:[]},te=null,Se=!1,ne=null;const an="/api/v1/events/invalidations";let q=null,et=!1,z=!1,K=[];const on=500,je={GRANTED:{badge:"bg-green-900/60 text-green-400 border border-green-500/30",dot:"bg-green-500",label:"Granted"},REVOKED:{badge:"bg-red-900/60 text-red-400 border border-red-500/30",dot:"bg-red-500",label:"Revoked"},CONSENT_ENTRY_CHANGED:{badge:"bg-amber-900/60 text-amber-400 border border-amber-500/30",dot:"bg-amber-500",label:"Changed"}},D="/api/v1/subjects";let de=[],Te=!1;async function rn(){const e=await fetch(D);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);return e.json()}async function tt(){w("subjects-table",!0),de=await J(rn,de),Te=!0,Be(),w("subjects-table",!1)}let R=null;function nt(e){h(e),e==="graph"&&!Qe&&ue(),e==="ledger"&&gn(),e==="subjects"&&!Te&&bn(),e==="playground"&&Kt()}function Ie(e){return e==="GRANTED"?je.GRANTED:e==="REVOKED"||e==="ALL_REVOKED"?je.REVOKED:je.CONSENT_ENTRY_CHANGED}function cn(e){Ze=e,document.getElementById("graph-mode-force").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="force"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),document.getElementById("graph-mode-tree").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="tree"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),Le()}async function ue(){var s,a;const e=document.getElementById("graph-search").value.trim(),t=document.getElementById("graph-root-type").value,n=new URLSearchParams;n.set("limit","200"),e&&t?(n.set("rootType",t),n.set("rootName",e),n.set("depth","3")):e&&(n.set("rootName",e),n.set("depth","3")),document.getElementById("graph-loading").classList.remove("hidden"),document.getElementById("graph-empty").classList.add("hidden");try{const r=await fetch(`${Ve}?${n}`);if(!r.ok)throw new Error((await r.json().catch(()=>({}))).message||r.statusText);const c=await r.json();if(S=c,te=((s=c.pageInfo)==null?void 0:s.nextCursor)||null,Se=((a=c.pageInfo)==null?void 0:a.hasMore)||!1,Qe=!0,!c.nodes||c.nodes.length===0){document.getElementById("graph-loading").classList.add("hidden"),document.getElementById("graph-empty").classList.remove("hidden"),document.getElementById("graph-pagination").classList.add("hidden");return}Le(),st(),document.getElementById("graph-loading").classList.add("hidden")}catch(r){document.getElementById("graph-loading").innerHTML=`<span class="text-red-400 text-sm">${o(r.message)}</span>`}}async function ln(){var t,n;if(!te)return;const e=new URLSearchParams;e.set("limit","200"),e.set("cursor",te);try{const a=await(await fetch(`${Ve}?${e}`)).json(),r=new Set(S.nodes.map(l=>l.type+":"+l.name));a.nodes.forEach(l=>{r.has(l.type+":"+l.name)||S.nodes.push(l)});const c=new Set(S.edges.map(l=>l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName));a.edges.forEach(l=>{c.has(l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName)||S.edges.push(l)}),te=((t=a.pageInfo)==null?void 0:t.nextCursor)||null,Se=((n=a.pageInfo)==null?void 0:n.hasMore)||!1,Le(),st()}catch(s){console.error("Failed to load more graph:",s)}}function dn(){document.getElementById("graph-search").value="",document.getElementById("graph-root-type").value="",te=null,ue()}function st(){const e=document.getElementById("graph-pagination"),t=document.getElementById("graph-stats"),n=S.pageInfo||{};t.textContent=`${S.nodes.length} nodes, ${S.edges.length} edges`+(n.totalNodes?` (of ${n.totalNodes} total)`:""),e.classList.toggle("hidden",!Se)}function Le(){Ze==="force"?un():pn()}function un(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,a=n.clientHeight;t.attr("viewBox",[0,0,s,a]);const r=new Map;S.nodes.forEach((i,g)=>{r.set(i.type+":"+i.name,g)});const c=S.nodes.map(i=>({id:i.type+":"+i.name,type:i.type,name:i.name,properties:i.properties||{}})),l=S.edges.filter(i=>r.has(i.sourceType+":"+i.sourceName)&&r.has(i.targetType+":"+i.targetName)).map(i=>({source:i.sourceType+":"+i.sourceName,target:i.targetType+":"+i.targetName,properties:i.properties||{}})),u=t.append("g");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",i=>{u.attr("transform",i.transform)}));const m=u.append("g").selectAll("line").data(l).join("line").attr("stroke","#374151").attr("stroke-width",1).attr("stroke-opacity",.6),y=u.append("g").selectAll("circle").data(c).join("circle").attr("r",i=>i.type==="subject"?6:8).attr("fill",i=>Xe[i.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5).style("cursor","pointer").call(e.drag().on("start",(i,g)=>{i.active||ne.alphaTarget(.3).restart(),g.fx=g.x,g.fy=g.y}).on("drag",(i,g)=>{g.fx=i.x,g.fy=i.y}).on("end",(i,g)=>{i.active||ne.alphaTarget(0),g.fx=null,g.fy=null})),p=u.append("g").selectAll("text").data(c).join("text").text(i=>i.name.length>20?i.name.slice(0,18)+"…":i.name).attr("font-size",10).attr("fill","#9ca3af").attr("dx",12).attr("dy",4).style("pointer-events","none");y.on("mouseover",(i,g)=>{const b=document.getElementById("graph-tooltip");document.getElementById("tooltip-type").textContent=g.type,document.getElementById("tooltip-name").textContent=g.name;const d=document.getElementById("tooltip-props");d.innerHTML=Object.entries(g.properties).map(([j,C])=>`<div class="flex justify-between gap-4"><span class="text-gray-500">${o(j)}</span><span class="text-white">${o(String(C))}</span></div>`).join(""),b.classList.remove("hidden"),b.style.left=i.pageX+12+"px",b.style.top=i.pageY-10+"px"}).on("mouseout",()=>{document.getElementById("graph-tooltip").classList.add("hidden")}),ne&&ne.stop(),ne=e.forceSimulation(c).force("link",e.forceLink(l).id(i=>i.id).distance(80)).force("charge",e.forceManyBody().strength(-200)).force("center",e.forceCenter(s/2,a/2)).force("collision",e.forceCollide(15)).on("tick",()=>{m.attr("x1",i=>i.source.x).attr("y1",i=>i.source.y).attr("x2",i=>i.target.x).attr("y2",i=>i.target.y),y.attr("cx",i=>i.x).attr("cy",i=>i.y),p.attr("x",i=>i.x).attr("y",i=>i.y)})}function pn(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,a=n.clientHeight;t.attr("viewBox",[0,0,s,a]);const r=["jurisdiction","regulation","purpose","field","subject"],c={name:"Consent Graph",type:"root",children:[]},l={};r.forEach(d=>{l[d]=[]}),S.nodes.forEach(d=>{l[d.type]?l[d.type].push({name:d.name,type:d.type,properties:d.properties||{}}):(l.other||(l.other=[]),l.other.push({name:d.name,type:d.type,properties:d.properties||{}}))});const u=new Map;S.edges.forEach(d=>{const j=d.sourceType+":"+d.sourceName,C=d.targetType+":"+d.targetName;u.has(j)||u.set(j,[]),u.get(j).push(C)});const m=new Set;function y(d){if(m.has(d))return null;m.add(d);const[j,...C]=d.split(":"),ae={name:C.join(":"),type:j,children:[]};return(u.get(d)||[]).forEach(Zn=>{const ft=y(Zn);ft&&ae.children.push(ft)}),ae}if(r.forEach(d=>{var j;(j=l[d])==null||j.forEach(C=>{const X=d+":"+C.name;if(!m.has(X)){const ae=y(X);ae&&c.children.push(ae)}})}),c.children.length===0){document.getElementById("graph-empty").classList.remove("hidden");return}const p=e.hierarchy(c);e.tree().size([a-40,s-200])(p);const g=t.append("g").attr("transform","translate(80, 20)");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",d=>{g.attr("transform",d.transform)})),g.selectAll(".tree-link").data(p.links()).join("path").attr("class","tree-link").attr("d",e.linkHorizontal().x(d=>d.y).y(d=>d.x)).attr("fill","none").attr("stroke","#374151").attr("stroke-width",1.5);const b=g.selectAll(".tree-node").data(p.descendants()).join("g").attr("class","tree-node").attr("transform",d=>`translate(${d.y},${d.x})`);b.append("circle").attr("r",6).attr("fill",d=>Xe[d.data.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5),b.append("text").attr("dy",4).attr("x",d=>d.children?-10:10).attr("text-anchor",d=>d.children?"end":"start").attr("font-size",10).attr("fill","#9ca3af").text(d=>d.data.name==="Consent Graph"?"":d.data.name.length>25?d.data.name.slice(0,23)+"…":d.data.name)}function gn(){if(!et){et=!0;try{q=new EventSource(an),q.onopen=()=>{pe("connected")},q.onmessage=e=>{try{const t=JSON.parse(e.data);if(!["GRANTED","REVOKED","ALL_REVOKED","CONSENT_ENTRY_CHANGED"].includes(t.action))return;mn(t)}catch(t){console.warn("Failed to parse SSE event:",t)}},q.onerror=()=>{pe("disconnected"),setTimeout(()=>{q&&q.readyState!==EventSource.CLOSED&&pe("reconnecting")},2e3)}}catch(e){pe("error"),console.error("SSE initialization failed:",e)}}}function pe(e){const t=document.getElementById("sse-status"),n={connected:{dot:"bg-green-500",text:"text-green-400",label:"Live"},disconnected:{dot:"bg-red-500",text:"text-red-400",label:"Disconnected"},reconnecting:{dot:"bg-amber-500 animate-pulse",text:"text-amber-400",label:"Reconnecting…"},error:{dot:"bg-red-500",text:"text-red-400",label:"Error"}},s=n[e]||n.disconnected;t.innerHTML=`<span class="w-2 h-2 rounded-full ${s.dot}"></span><span class="${s.text}">${s.label}</span>`}function mn(e){const t={...e,receivedAt:new Date().toISOString()};K.unshift(t),K.length>on&&K.pop(),at()}function at(){const e=document.getElementById("ledger-feed"),t=document.getElementById("ledger-empty"),n=yn();if(n.length===0){t.classList.remove("hidden"),e.querySelectorAll(".ledger-event").forEach(s=>s.remove()),document.getElementById("ledger-count").textContent="";return}t.classList.add("hidden"),e.innerHTML=n.map(s=>{const a=Ie(s.action);return`
            <div class="ledger-event flex items-start gap-4 px-5 py-3 hover:bg-gray-50 transition-colors">
                <div class="mt-1.5 flex-shrink-0">
                    <span class="block w-2.5 h-2.5 rounded-full ${a.dot}"></span>
                </div>
                <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2 mb-1">
                        <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${a.badge}">${a.label}</span>
                        ${s.subject?`<span class="text-xs text-white font-mono truncate">${o(s.subject)}</span>`:""}
                    </div>
                    <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                        ${s.purpose?`<span>Purpose: <span class="text-gray-300">${o(s.purpose)}</span></span>`:""}
                        ${s.field?`<span>Field: <span class="text-gray-300">${o(s.field)}</span></span>`:""}
                        ${s.entity?`<span>Entity: <span class="text-gray-300">${o(s.entity)}</span></span>`:""}
                        ${s.category?`<span>Category: <span class="text-gray-300">${o(s.category)}</span></span>`:""}
                    </div>
                </div>
                <div class="flex-shrink-0 text-xs text-gray-600 whitespace-nowrap">
                    ${Z(s.receivedAt)}
                </div>
            </div>`}).join(""),document.getElementById("ledger-count").textContent=`${n.length} events shown`+(n.length<K.length?` (${K.length} total)`:""),z||(e.scrollTop=0)}function yn(){const e=document.getElementById("ledger-filter-action").value,t=document.getElementById("ledger-filter-subject").value.trim().toLowerCase(),n=document.getElementById("ledger-filter-purpose").value.trim().toLowerCase();return K.filter(s=>!(e&&s.action!==e||t&&(!s.subject||!s.subject.toLowerCase().includes(t))||n&&(!s.purpose||!s.purpose.toLowerCase().includes(n))))}function fn(){at()}function xn(){z=!z;const e=document.getElementById("ledger-pause-btn");e.classList.toggle("text-cv-500",z),e.classList.toggle("border-cv-500/30",z),e.title=z?"Resume auto-scroll":"Pause auto-scroll"}async function bn(){try{const e=await fetch(D);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);de=await e.json(),Te=!0,Be()}catch(e){document.getElementById("subjects-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function Be(e){const t=document.getElementById("subjects-table");let n=de;if(e){const s=e.toLowerCase();n=n.filter(a=>a.subjectName.toLowerCase().includes(s)||(a.description||"").toLowerCase().includes(s))}if(!n.length){t.innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">${e?"No matching subjects.":"No subjects registered yet."}</td></tr>`;return}t.innerHTML=n.map(s=>`
        <tr class="hover:bg-gray-50 transition-colors cursor-pointer" onclick="viewSubject('${o(s.subjectName)}')">
            <td class="px-6 py-4 font-medium text-gray-900 font-mono">${o(s.subjectName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(s.description||"—")}</td>
            <td class="px-6 py-4 text-gray-500 text-xs">${Z(s.createdAt)}</td>
            <td class="px-6 py-4 text-right" onclick="event.stopPropagation()">
                <button onclick='openSubjectPanel("edit", ${JSON.stringify(s).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteSubject("${o(s.subjectName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function hn(){Be(document.getElementById("subjects-search").value.trim())}async function vn(e){R=e,document.getElementById("subject-detail").classList.remove("hidden"),document.getElementById("subject-detail-title").textContent=e,rt("summary")}function ot(){document.getElementById("subject-detail").classList.add("hidden"),R=null}function rt(e){document.querySelectorAll(".dtab-btn").forEach(t=>{t.classList.remove("border-cv-500","text-cv-500"),t.classList.add("border-transparent","text-gray-400")}),document.getElementById("dtab-"+e).classList.add("border-cv-500","text-cv-500"),document.getElementById("dtab-"+e).classList.remove("border-transparent","text-gray-400"),e==="summary"?wn():e==="history"&&En()}async function wn(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${D}/${encodeURIComponent(R)}/summary`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No active consent entries for this subject.</p>';return}e.innerHTML=`
            <div class="space-y-0 divide-y divide-gray-700/30">
                ${s.map(a=>`
                    <div class="flex items-center gap-4 py-3">
                        <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${Ie(a.action).badge}">${o(a.action)}</span>
                        <div class="flex-1 text-sm">
                            <span class="text-white">${o(a.purposeName)}</span>
                            <span class="text-gray-500 mx-1">→</span>
                            <span class="text-gray-400">${o(a.policyType==="FIELD"?(a.entityName?a.entityName+"/":"")+(a.fieldName||""):a.categoryName||"")}</span>
                        </div>
                        <span class="text-xs text-gray-600">${Z(a.createdAt)}</span>
                        ${a.expiresAt?`<span class="text-xs text-amber-500/70">expires ${Z(a.expiresAt)}</span>`:""}
                    </div>`).join("")}
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${o(t.message)}</p>`}}async function En(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${D}/${encodeURIComponent(R)}/history`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No history for this subject.</p>';return}e.innerHTML=`
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
                                <td class="py-2 pr-4"><span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${Ie(a.action).badge}">${o(a.action)}</span></td>
                                <td class="py-2 pr-4 text-gray-900">${o(a.purposeName)}</td>
                                <td class="py-2 pr-4 text-gray-600">${o(a.policyType==="FIELD"?(a.entityName?a.entityName+"/":"")+(a.fieldName||""):a.categoryName||"")}</td>
                                <td class="py-2 pr-4 text-gray-500">${o(a.actor||"—")}</td>
                                <td class="py-2 pr-4 text-gray-500 font-mono">${o(a.ipAddress||"—")}</td>
                                <td class="py-2 text-gray-500">${Z(a.createdAt)}</td>
                            </tr>`).join("")}
                    </tbody>
                </table>
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${o(t.message)}</p>`}}async function Nn(){if(R)try{const e=await fetch(`${D}/${encodeURIComponent(R)}/export`);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);const t=await e.json(),n=new Blob([JSON.stringify(t,null,2)],{type:"application/json"}),s=URL.createObjectURL(n),a=document.createElement("a");a.href=s,a.download=`dsar-export-${R}.json`,a.click(),URL.revokeObjectURL(s)}catch(e){alert("Export failed: "+e.message)}}function $n(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");e==="add"?(n.textContent="Register Subject",s.innerHTML=ct()):e==="edit"&&(n.textContent="Edit Subject",s.innerHTML=ct(t)),ye()}function it(){v()}function ct(e){const t=e?o(e.subjectName):"",n=e?o(e.description||""):"";return`
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
        </form>`}async function Sn(e,t,n){e.preventDefault();const s=document.getElementById("pf-sub-name").value.trim(),a=document.getElementById("pf-sub-desc").value.trim(),r=document.getElementById("pf-sub-msg");try{if(t){const c=await fetch(`${D}/${encodeURIComponent(n)}`,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({description:a})});if(!c.ok)throw new Error((await c.json().catch(()=>({}))).message||c.statusText)}else{const c=await fetch(D,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({subjectName:s,description:a})});if(!c.ok)throw new Error((await c.json().catch(()=>({}))).message||c.statusText)}it(),await tt()}catch(c){r.textContent=c.message,r.className="text-sm mt-3 text-red-400",r.classList.remove("hidden")}}function jn(e){M(`Delete subject "${e}"? This cannot be undone.`,async()=>{try{const t=await fetch(`${D}/${encodeURIComponent(e)}`,{method:"DELETE"});if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);await tt(),R===e&&ot()}catch(t){alert(t.message)}})}function Tn(){Object.assign(window,{showTab:nt,closeDeleteModal:re,setGraphMode:cn,loadGraph:ue,resetGraph:dn,loadMoreGraph:ln,applyLedgerFilters:fn,toggleLedgerPause:xn,filterSubjects:hn,openSubjectPanel:$n,closeSubjectPanel:it,viewSubject:vn,closeSubjectDetail:ot,showDetailTab:rt,exportSubjectData:Nn,saveSubject:Sn,deleteSubject:jn,evaluateAndMask:Xt,remask:tn,loadSampleTemplate:Vt,copyMaskedOutput:nn}),me(),fe(()=>{const e=window.location.hash.replace("#","");["graph","ledger","subjects","playground"].includes(e)?nt(e):ue()})}const In=Object.freeze(Object.defineProperty({__proto__:null,init:Tn},Symbol.toStringTag,{value:"Module"})),x=O("/api/v1/governance"),lt="/api/v1/catalogue";let ke=[],Ce=[],Y=[],P=[],k=[];async function Ln(){try{const[e,t]=await Promise.all([fetch(lt+"/fields").then(n=>n.ok?n.json():[]),fetch(lt+"/categories").then(n=>n.ok?n.json():[])]);ke=e,Ce=t}catch(e){console.warn("Failed to load catalogue data:",e)}}async function dt(){w("jurisdictions-table",!0),Y=await J(()=>x("/jurisdictions"),Y),ut(),w("jurisdictions-table",!1)}async function ge(){w("regulations-table",!0),P=await J(()=>x("/regulations"),P),gt(),w("regulations-table",!1)}async function V(){w("purposes-table",!0),w("policies-table",!0),k=await J(()=>x("/purposes"),k),mt(),yt(),w("purposes-table",!1),w("policies-table",!1)}function se(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"jurisdiction-add":()=>{n.textContent="Add Jurisdiction",s.innerHTML=pt()},"jurisdiction-edit":()=>{n.textContent="Edit Jurisdiction",s.innerHTML=pt(t)},"regulation-add":()=>{n.textContent="Add Regulation",s.innerHTML=Mn()},"purpose-add":()=>{n.textContent="Add Purpose",s.innerHTML=_n()},"policy-add":()=>{n.textContent="Add Policy",s.innerHTML=zn()},"regulation-jurisdictions":()=>{n.textContent="Manage Jurisdictions",s.innerHTML=Rn(t)},"purpose-regulations":()=>{n.textContent="Manage Regulations",s.innerHTML=Gn(t)}}[e]||(()=>{s.innerHTML=""}))(),ye()}async function Bn(){try{Y=await x("/jurisdictions"),ut()}catch(e){document.getElementById("jurisdictions-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function ut(){const e=document.getElementById("jurisdictions-table");if(!Y.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No jurisdictions defined yet.</td></tr>';return}e.innerHTML=Y.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.jurisdictionName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("jurisdiction-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteJurisdiction("${o(t.jurisdictionName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function pt(e){const t=e?o(e.jurisdictionName):"",n=e?o(e.description||""):"";return`
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
        </form>`}async function kn(e,t,n){e.preventDefault();const s=document.getElementById("pf-jur-name").value.trim(),a=document.getElementById("pf-jur-desc").value.trim(),r=document.getElementById("pf-jur-msg");try{t?await x(`/jurisdictions/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:a})}):await x("/jurisdictions",{method:"POST",body:JSON.stringify({jurisdictionName:s,description:a})}),v(),await dt()}catch(c){I(r,c.message,!0)}}function Cn(e){M(`Delete jurisdiction "${e}"? This cannot be undone.`,async()=>{try{await x(`/jurisdictions/${encodeURIComponent(e)}`,{method:"DELETE"}),await dt()}catch(t){alert(t.message)}})}async function On(){try{P=await x("/regulations"),gt()}catch(e){document.getElementById("regulations-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function gt(){const e=document.getElementById("regulations-table");if(!P.length){e.innerHTML='<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">No regulations defined yet.</td></tr>';return}e.innerHTML=P.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.regulationName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.activeJurisdictions||[]).map(n=>Q(n.jurisdictionName,"teal")).join("")}
                    <button onclick='openPanel("regulation-jurisdictions", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteRegulation("${o(t.regulationName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Mn(){return`
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
        </form>`}async function An(e){e.preventDefault();const t=document.getElementById("pf-reg-name").value.trim(),n=document.getElementById("pf-reg-desc").value.trim(),s=document.getElementById("pf-reg-msg");try{await x("/regulations",{method:"POST",body:JSON.stringify({regulationName:t,description:n})}),v(),await ge()}catch(a){I(s,a.message,!0)}}function Dn(e){M(`Delete regulation "${e}"? This cannot be undone.`,async()=>{try{await x(`/regulations/${encodeURIComponent(e)}`,{method:"DELETE"}),await ge()}catch(t){alert(t.message)}})}function Rn(e){const t=new Set((e.activeJurisdictions||[]).map(s=>s.jurisdictionName)),n=Y.filter(s=>!t.has(s.jurisdictionName)).map(s=>`<option value="${o(s.jurisdictionName)}">${o(s.jurisdictionName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage jurisdictions for <strong class="text-gray-900">${o(e.regulationName)}</strong></p>
        <div id="rj-list" class="space-y-2 mb-6">
            ${(e.activeJurisdictions||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <span class="text-sm text-gray-900">${o(s.jurisdictionName)}</span>
                    <button onclick="removeJurisdictionFromRegulation('${o(e.regulationName)}','${o(s.jurisdictionName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No jurisdictions linked.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Add a jurisdiction</p>
        <form onsubmit="addJurisdictionToRegulation(event, '${o(e.regulationName)}')">
            <div class="flex gap-2">
                <select id="rj-select" class="flex-1 bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${n}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Add</button>
            </div>
            <span id="rj-msg" class="hidden"></span>
        </form>`}async function Pn(e,t){e.preventDefault();const s=document.getElementById("rj-select").value;if(s)try{await x("/regulations/jurisdictions",{method:"POST",body:JSON.stringify({regulationName:t,jurisdictionName:s})}),v(),await ge();const a=P.find(r=>r.regulationName===t);a&&se("regulation-jurisdictions",a)}catch(a){I(document.getElementById("rj-msg"),a.message,!0)}}async function Jn(e,t){try{await x(`/regulations/${encodeURIComponent(e)}/jurisdictions/${encodeURIComponent(t)}`,{method:"DELETE"}),v(),await ge();const n=P.find(s=>s.regulationName===e);n&&se("regulation-jurisdictions",n)}catch(n){alert(n.message)}}async function Hn(){try{k=await x("/purposes"),mt(),yt()}catch(e){document.getElementById("purposes-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${o(e.message)}</td></tr>`}}function mt(){const e=document.getElementById("purposes-table");if(!k.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No purposes defined yet.</td></tr>';return}e.innerHTML=k.map(t=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(t.purposeName)}</td>
            <td class="px-6 py-4 text-gray-400">${o(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.regulations||[]).map(n=>Q(n.regulationName,"blue")).join("")}
                    <button onclick='openPanel("purpose-regulations", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-300 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.policies||[]).map(n=>Q(n.policyType==="FIELD"?n.field?n.field.entityName+"/"+n.field.fieldName:"field":n.category?n.category.categoryName:"category","purple")).join("")}
                    <span class="text-xs text-gray-500">${(t.policies||[]).length}</span>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deletePurpose("${o(t.purposeName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function _n(){return`
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
        </form>`}async function Fn(e){e.preventDefault();const t=document.getElementById("pf-pur-name").value.trim(),n=document.getElementById("pf-pur-desc").value.trim(),s=document.getElementById("pf-pur-msg");try{await x("/purposes",{method:"POST",body:JSON.stringify({purposeName:t,description:n})}),v(),await V()}catch(a){I(s,a.message,!0)}}function Un(e){M(`Delete purpose "${e}"? This will also remove all linked policies and regulations.`,async()=>{try{await x(`/purposes/${encodeURIComponent(e)}`,{method:"DELETE"}),await V()}catch(t){alert(t.message)}})}function Gn(e){const t=new Set((e.regulations||[]).map(s=>s.regulationName)),n=P.filter(s=>!t.has(s.regulationName)).map(s=>`<option value="${o(s.regulationName)}">${o(s.regulationName)}</option>`).join("");return`
        <p class="text-sm text-gray-500 mb-4">Manage regulation links for <strong class="text-gray-900">${o(e.purposeName)}</strong></p>
        <div id="pr-list" class="space-y-2 mb-6">
            ${(e.regulations||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                    <div>
                        <span class="text-sm text-gray-900">${o(s.regulationName)}</span>
                        ${s.lawfulBasis?`<span class="ml-2 text-xs text-gray-500">basis: ${o(s.lawfulBasis)}</span>`:""}
                    </div>
                    <button onclick="unlinkPurposeRegulation('${o(e.purposeName)}','${o(s.regulationName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Unlink</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No regulations linked.</p>'}
        </div>
        <hr class="border-gray-200 mb-4">
        <p class="text-sm text-gray-500 mb-3">Link a regulation</p>
        <form onsubmit="linkPurposeRegulation(event, '${o(e.purposeName)}')">
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
        </form>`}async function Wn(e,t){e.preventDefault();const n=document.getElementById("pr-select").value;if(!n)return;const s=document.getElementById("pr-basis").value.trim()||null,a=document.getElementById("pr-granted").value.trim()||null,r=document.getElementById("pr-denied").value.trim()||null;try{await x("/purposes/regulations",{method:"POST",body:JSON.stringify({purposeName:t,regulationName:n,lawfulBasis:s,onGranted:a,onDenied:r})}),v(),await V();const c=k.find(l=>l.purposeName===t);c&&se("purpose-regulations",c)}catch(c){I(document.getElementById("pr-msg"),c.message,!0)}}async function qn(e,t){try{await x(`/purposes/${encodeURIComponent(e)}/regulations/${encodeURIComponent(t)}`,{method:"DELETE"}),v(),await V();const n=k.find(s=>s.purposeName===e);n&&se("purpose-regulations",n)}catch(n){alert(n.message)}}function yt(){const e=document.getElementById("policies-table"),t=[];if(k.forEach(n=>{(n.policies||[]).forEach(s=>{t.push({...s,purposeName:n.purposeName})})}),!t.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No policies defined yet. Add policies from the Purposes tab.</td></tr>';return}e.innerHTML=t.map(n=>`
        <tr class="hover:bg-gray-50 transition-colors">
            <td class="px-6 py-4 font-medium text-gray-900">${o(n.purposeName)}</td>
            <td class="px-6 py-4">${Q(n.policyType,n.policyType==="FIELD"?"teal":"purple")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"?o(n.field?n.field.fieldName:"—"):o(n.category?n.category.categoryName:"—")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"&&n.field?o(n.field.entityName):"—"}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='removePolicy("${o(n.purposeName)}", ${JSON.stringify(n).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Remove</button>
            </td>
        </tr>`).join("")}function zn(){const e=ke.map(n=>{const s=(n.entityName?n.entityName+"/":"")+n.fieldName;return`<option value="${JSON.stringify({fieldName:n.fieldName,entityName:n.entityName||""}).replace(/"/g,"&quot;")}">${o(s)}</option>`}).join(""),t=Ce.map(n=>`<option value="${o(n.categoryName)}">${o(n.categoryName)}</option>`).join("");return`
        <form onsubmit="addPolicy(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-500 mb-1">Purpose</label>
                    <select id="pf-pol-purpose" required
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select purpose…</option>
                        ${k.map(n=>`<option value="${o(n.purposeName)}">${o(n.purposeName)}</option>`).join("")}
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
                    ${ke.length?"":'<p class="text-xs text-amber-400 mt-1">No fields registered. Add fields in the Catalogue first.</p>'}
                </div>
                <div id="pf-pol-cat-group" class="hidden">
                    <label class="block text-sm text-gray-500 mb-1">Category</label>
                    <select id="pf-pol-cat-select"
                        class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 focus:border-cv-500 focus:outline-none">
                        <option value="">Select category…</option>
                        ${t}
                    </select>
                    ${Ce.length?"":'<p class="text-xs text-amber-400 mt-1">No categories registered. Add categories in the Catalogue first.</p>'}
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Add Policy</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 border border-gray-300 rounded-lg hover:bg-gray-100 transition-colors">Cancel</button>
            </div>
            <span id="pf-pol-msg" class="hidden"></span>
        </form>`}function Kn(){const e=document.getElementById("pf-pol-type").value;document.getElementById("pf-pol-field-group").classList.toggle("hidden",e!=="FIELD"),document.getElementById("pf-pol-cat-group").classList.toggle("hidden",e!=="CATEGORY"),document.getElementById("pf-pol-field-select").required=e==="FIELD",document.getElementById("pf-pol-cat-select").required=e==="CATEGORY"}async function Yn(e){e.preventDefault();const t=document.getElementById("pf-pol-purpose").value,n=document.getElementById("pf-pol-type").value,s=document.getElementById("pf-pol-msg"),a={purposeName:t,policyType:n};if(n==="FIELD"){const r=document.getElementById("pf-pol-field-select").value;if(!r){I(s,"Please select a field.",!0);return}const c=JSON.parse(r);a.fieldName=c.fieldName,a.entityName=c.entityName,a.categoryName=null}else{if(a.categoryName=document.getElementById("pf-pol-cat-select").value,!a.categoryName){I(s,"Please select a category.",!0);return}a.fieldName=null,a.entityName=null}try{await x("/purposes/policies",{method:"POST",body:JSON.stringify(a)}),v(),await V()}catch(r){I(s,r.message,!0)}}async function Vn(e,t){M(`Remove this ${t.policyType.toLowerCase()} policy from "${e}"?`,async()=>{try{const n={purposeName:e,policyType:t.policyType,fieldName:t.field?t.field.fieldName:null,entityName:t.field?t.field.entityName:null,categoryName:t.category?t.category.categoryName:null};await x("/purposes/policies",{method:"DELETE",body:JSON.stringify(n)}),await V()}catch(n){alert(n.message)}})}function Xn(){Object.assign(window,{showTab:h,openPanel:se,closePanel:v,closeDeleteModal:re,saveJurisdiction:kn,deleteJurisdiction:Cn,saveRegulation:An,deleteRegulation:Dn,addJurisdictionToRegulation:Pn,removeJurisdictionFromRegulation:Jn,savePurpose:Fn,deletePurpose:Un,linkPurposeRegulation:Wn,unlinkPurposeRegulation:qn,togglePolicyFields:Kn,addPolicy:Yn,removePolicy:Vn}),me(),fe(()=>{Bn(),On(),Hn(),Ln();const e=window.location.hash.replace("#","");["jurisdictions","regulations","purposes","policies"].includes(e)&&h(e)})}const Qn=Object.freeze(Object.defineProperty({__proto__:null,init:Xn},Symbol.toStringTag,{value:"Module"}));return f.catalogue=Dt,f.consent=In,f.governance=Qn,Object.defineProperty(f,Symbol.toStringTag,{value:"Module"}),f})({});
