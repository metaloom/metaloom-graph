{{/* Standard name helpers. */}}
{{- define "graph.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "graph.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "graph.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "graph.labels" -}}
helm.sh/chart: {{ include "graph.chart" . }}
{{ include "graph.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: metaloom
{{- end -}}

{{- define "graph.selectorLabels" -}}
app.kubernetes.io/name: {{ include "graph.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: graph
{{- end -}}

{{- define "graph.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "graph.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The image, with the tag defaulting to the chart's appVersion. */}}
{{- define "graph.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{/*
  One writer process, enforced by a LOCK file in the store directory. A second replica over the same
  ReadWriteOnce volume does not split the load — it fails to open the store and crash-loops while the
  first pod keeps serving, which looks like a broken image rather than a configuration mistake.

  Refused at template time, with the reason, because that is cheaper than finding out in a cluster.
*/}}
{{- define "graph.validateReplicas" -}}
{{- if ne (int .Values.replicaCount) 1 -}}
{{- fail (printf "replicaCount is %d, and MetaLoom // Graph supports exactly 1. The store allows one writer process, enforced by a LOCK file in the store directory; a second pod over the same volume will crash-loop on that lock rather than share the work. Scale up by giving a graph its own release, not its own replica." (int .Values.replicaCount)) -}}
{{- end -}}
{{- end -}}
